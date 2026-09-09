package com.aiconnect.llmgateway.routing;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.external.ProjectExternalAccessService;
import com.aiconnect.llmgateway.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class RoutingService {
    private final ServiceTargetRepository targets;
    private final ModelDeploymentRepository deployments;
    private final RuntimeEndpointRepository endpoints;
    private final ExternalProviderRepository providers;
    private final ProjectExternalAccessService externalAccess;
    private final ActiveRequestRegistry activeRequests;
    private final WeightedTargetSelector weightedSelector;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public RoutingService(ServiceTargetRepository targets, ModelDeploymentRepository deployments,
                          RuntimeEndpointRepository endpoints, ExternalProviderRepository providers,
                          ProjectExternalAccessService externalAccess, ActiveRequestRegistry activeRequests,
                          WeightedTargetSelector weightedSelector, ObjectMapper objectMapper) {
        this.targets = targets; this.deployments = deployments; this.endpoints = endpoints;
        this.providers = providers; this.externalAccess = externalAccess; this.activeRequests = activeRequests;
        this.weightedSelector = weightedSelector; this.objectMapper = objectMapper;
    }

    RoutingService(ServiceTargetRepository targets, ModelDeploymentRepository deployments,
                   RuntimeEndpointRepository endpoints, ActiveRequestRegistry activeRequests,
                   WeightedTargetSelector weightedSelector, ObjectMapper objectMapper) {
        this(targets, deployments, endpoints, null, null, activeRequests, weightedSelector, objectMapper);
    }

    /** Local-only compatibility overload used by routing administration and unit tests. */
    public List<ResolvedTarget> candidates(LlmService service, Set<String> requestCapabilities) {
        return candidates(service, requestCapabilities, null);
    }

    /** Existing routing API retained for callers that only need eligible targets. */
    public List<ResolvedTarget> candidates(LlmService service, Set<String> requestCapabilities, UUID projectId) {
        return evaluateInternal(service, requestCapabilities, projectId,
                targets.findByServiceIdAndEnabledTrueOrderByPriorityAsc(service.getId())).eligibleTargets();
    }

    /** Evaluate every configured target and retain exclusion reasons for request diagnostics. */
    public RoutingDecision evaluate(LlmService service, Set<String> requestCapabilities, UUID projectId) {
        return evaluateInternal(service, requestCapabilities, projectId,
                targets.findByServiceIdOrderByPriorityAsc(service.getId()));
    }

    private RoutingDecision evaluateInternal(LlmService service, Set<String> requestCapabilities,
                                             UUID projectId, List<ServiceTarget> configured) {
        Set<String> requiredCapabilities = new TreeSet<>(readCapabilities(service.getRequiredCapabilitiesJson()));
        if (requestCapabilities != null) requiredCapabilities.addAll(requestCapabilities);

        boolean degradedAllowed = service.isAllowDegraded() || service.getFailoverPolicy() == FailoverPolicy.DEGRADED;
        String strictCompatibilityKey = service.getFailoverPolicy() == FailoverPolicy.STRICT
                ? referenceCompatibilityKey(configured, degradedAllowed) : null;
        boolean serviceHasLocalTargets = configured.stream()
                .filter(ServiceTarget::isEnabled)
                .map(target -> deployments.findById(target.getDeploymentId()).orElse(null))
                .anyMatch(deployment -> deployment != null && deployment.isEnabled() && !deployment.isExternal());

        List<ResolvedTarget> local = new ArrayList<>();
        List<ResolvedTarget> external = new ArrayList<>();
        List<RoutingDecision.TargetEvaluation> evaluations = new ArrayList<>();
        for (ServiceTarget target : configured) {
            ModelDeployment deployment = deployments.findById(target.getDeploymentId()).orElse(null);
            String providerType = deployment != null && deployment.isExternal() ? "EXTERNAL" : "LOCAL";
            List<String> availableCapabilities = deployment == null ? List.of()
                    : sortedCapabilities(deployment.getCapabilitiesJson(), deployment.getCapabilityOverridesJson());
            List<String> missingCapabilities = missing(requiredCapabilities, availableCapabilities);
            List<String> reasons = new ArrayList<>();
            RuntimeEndpoint endpoint = null;
            ExternalProvider provider = null;
            Integer activeCount = null;
            int limit = 0;

            if (!target.isEnabled()) reasons.add("TARGET_DISABLED");
            if (target.isDegraded() && !degradedAllowed) reasons.add("DEGRADED_NOT_ALLOWED");
            if (deployment == null) reasons.add("DEPLOYMENT_MISSING");
            if (deployment != null && !deployment.isEnabled()) reasons.add("DEPLOYMENT_DISABLED");
            if (deployment != null && strictCompatibilityKey != null && !strictCompatibilityKey.equals(deployment.getCompatibilityKey())) reasons.add("COMPATIBILITY_MISMATCH");
            if (deployment != null && !deployment.isLoaded()) reasons.add("DEPLOYMENT_NOT_LOADED");
            if (deployment != null && deployment.getHealthStatus() != HealthStatus.HEALTHY) reasons.add("DEPLOYMENT_UNHEALTHY");
            if (deployment != null && target.isFollowModelChanges() && hasLoadedReplacement(deployment)) reasons.add("TARGET_MODEL_STALE");
            if (deployment != null && !missingCapabilities.isEmpty()) reasons.add("CAPABILITY_MISSING");
            if (deployment != null) {
                limit = target.effectiveMaxConcurrency(deployment.getMaxConcurrency());
                activeCount = activeRequests.count(deployment.getId());
                if (limit > 0 && activeCount >= limit) reasons.add("CONCURRENCY_LIMIT_REACHED");
            }

            if (deployment != null && deployment.isExternal()) {
                if (providers == null || externalAccess == null) reasons.add("EXTERNAL_ACCESS_UNAVAILABLE");
                else {
                    provider = deployment.getExternalProviderId() == null ? null
                            : providers.findById(deployment.getExternalProviderId()).orElse(null);
                    if (provider == null) reasons.add("EXTERNAL_PROVIDER_MISSING");
                    else {
                        if (!provider.isEnabled()) reasons.add("EXTERNAL_PROVIDER_DISABLED");
                        if (provider.getHealthStatus() != HealthStatus.HEALTHY) reasons.add("EXTERNAL_PROVIDER_UNHEALTHY");
                        if (projectId == null) reasons.add("EXTERNAL_PROJECT_REQUIRED");
                        else {
                            boolean allowed = serviceHasLocalTargets
                                    ? externalAccess.allowsAutoFailover(projectId, provider.getId())
                                    : externalAccess.allowsManual(projectId, provider.getId());
                            if (!allowed) reasons.add(serviceHasLocalTargets ? "EXTERNAL_AUTO_FAILOVER_NOT_ALLOWED" : "EXTERNAL_MANUAL_ACCESS_NOT_ALLOWED");
                        }
                    }
                }
            } else if (deployment != null) {
                endpoint = deployment.getRuntimeEndpointId() == null ? null
                        : endpoints.findById(deployment.getRuntimeEndpointId()).orElse(null);
                if (endpoint == null) reasons.add("ENDPOINT_MISSING");
                else {
                    if (!endpoint.isEnabled()) reasons.add("ENDPOINT_DISABLED");
                    if (endpoint.getHealthStatus() != HealthStatus.HEALTHY) reasons.add("ENDPOINT_UNHEALTHY");
                }
            }

            boolean eligible = reasons.isEmpty();
            evaluations.add(new RoutingDecision.TargetEvaluation(target.getId(), deployment == null ? target.getDeploymentId() : deployment.getId(),
                    deployment == null ? "Unknown deployment" : deployment.getDisplayName(), providerType, target.getPriority(), target.getWeight(),
                    target.isEnabled(), target.isDegraded(), deployment != null && deployment.isEnabled(), deployment != null && deployment.isLoaded(),
                    deployment == null || deployment.getHealthStatus() == null ? null : deployment.getHealthStatus().name(),
                    endpoint == null ? null : endpoint.getDisplayName(), provider == null ? null : provider.getDisplayName(), activeCount, limit,
                    List.copyOf(requiredCapabilities), availableCapabilities, missingCapabilities, eligible, List.copyOf(reasons)));
            if (!eligible) continue;
            if (deployment.isExternal()) {
                external.add(new ResolvedTarget(target, deployment, null, provider, limit, serviceHasLocalTargets ? "AUTO_FAILOVER" : "MANUAL_EXTERNAL"));
            } else local.add(new ResolvedTarget(target, deployment, endpoint, null, limit, "LOCAL"));
        }

        List<ResolvedTarget> ordered = new ArrayList<>(weightedSelector.order(local));
        // External targets are always appended after local targets. Their numeric priority
        // can never accidentally turn an opt-in failover target into the primary route.
        ordered.addAll(weightedSelector.order(external));
        return new RoutingDecision(List.copyOf(ordered), Set.copyOf(requiredCapabilities), degradedAllowed,
                service.getFailoverPolicy().name(), service.getRetryPolicy().name(), List.copyOf(evaluations));
    }

    public boolean acquire(ResolvedTarget target) { return activeRequests.tryAcquire(target.deployment().getId(), target.maxConcurrency()); }
    public void release(ResolvedTarget target) { activeRequests.release(target.deployment().getId()); }

    private String referenceCompatibilityKey(List<ServiceTarget> configured, boolean degradedAllowed) {
        for (ServiceTarget target : configured) {
            if (!target.isEnabled() || (target.isDegraded() && !degradedAllowed)) continue;
            ModelDeployment deployment = deployments.findById(target.getDeploymentId()).orElse(null);
            if (deployment != null && deployment.isEnabled()) return deployment.getCompatibilityKey();
        }
        return null;
    }

    private boolean hasLoadedReplacement(ModelDeployment deployment) {
        if (deployment.isExternal() || deployment.getRuntimeEndpointId() == null) return false;
        if (deployment.isLoaded() && deployment.getHealthStatus() == HealthStatus.HEALTHY) return false;
        return deployments.findByRuntimeEndpointId(deployment.getRuntimeEndpointId()).stream()
                .anyMatch(candidate -> !candidate.getId().equals(deployment.getId())
                        && !candidate.isExternal() && candidate.isEnabled() && candidate.isLoaded()
                        && candidate.getHealthStatus() == HealthStatus.HEALTHY);
    }

    private List<String> sortedCapabilities(String... jsonValues) {
        Set<String> values = new TreeSet<>();
        for (String json : jsonValues) values.addAll(readCapabilities(json));
        return List.copyOf(values);
    }

    private List<String> missing(Set<String> required, List<String> available) {
        Set<String> values = new TreeSet<>(required);
        values.removeAll(available);
        return List.copyOf(values);
    }

    private Set<String> readCapabilities(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try { return new HashSet<>(objectMapper.readValue(json, new TypeReference<List<String>>() { })); }
        catch (Exception exception) { return Set.of(); }
    }
    /** Evaluate with a request-scoped privacy boundary while preserving the legacy API. */
    public RoutingDecision evaluate(LlmService service, Set<String> requestCapabilities, UUID projectId,
                                    RoutingConstraint constraint) {
        RoutingDecision base = evaluate(service, requestCapabilities, projectId);
        if (constraint == null || (constraint.externalAllowed() && constraint.externalFailoverAllowed())) return base;
        boolean localConfigured = base.evaluations().stream().anyMatch(item -> !"EXTERNAL".equals(item.providerType()));
        boolean blockExternal = !constraint.externalAllowed() || (localConfigured && !constraint.externalFailoverAllowed());
        if (!blockExternal) return base;
        String reason = !constraint.externalAllowed()
                ? "DATA_PROTECTION_EXTERNAL_BLOCKED" : "DATA_PROTECTION_EXTERNAL_FAILOVER_BLOCKED";
        List<ResolvedTarget> eligible = base.eligibleTargets().stream()
                .filter(target -> !target.external()).toList();
        List<RoutingDecision.TargetEvaluation> evaluations = base.evaluations().stream()
                .map(item -> item.providerType().equals("EXTERNAL")
                        ? withReason(item, reason) : item)
                .toList();
        return new RoutingDecision(eligible, base.requiredCapabilities(), base.degradedAllowed(),
                base.failoverPolicy(), base.retryPolicy(), evaluations);
    }

    private RoutingDecision.TargetEvaluation withReason(RoutingDecision.TargetEvaluation item, String reason) {
        List<String> reasons = new ArrayList<>(item.reasonCodes());
        if (!reasons.contains(reason)) reasons.add(reason);
        return new RoutingDecision.TargetEvaluation(item.targetId(), item.deploymentId(), item.displayName(),
                item.providerType(), item.priority(), item.weight(), item.targetEnabled(), item.degraded(),
                item.deploymentEnabled(), item.loaded(), item.deploymentHealth(), item.endpointDisplayName(),
                item.providerDisplayName(), item.activeRequests(), item.maxConcurrency(), item.requiredCapabilities(),
                item.availableCapabilities(), item.missingCapabilities(), false, List.copyOf(reasons));
    }
}
