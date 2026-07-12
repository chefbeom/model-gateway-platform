package com.aiconnect.llmgateway.routing;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.external.ProjectExternalAccessService;
import com.aiconnect.llmgateway.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

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

    public List<ResolvedTarget> candidates(LlmService service, Set<String> requestCapabilities, UUID projectId) {
        Set<String> requiredCapabilities = new HashSet<>(readCapabilities(service.getRequiredCapabilitiesJson()));
        requiredCapabilities.addAll(requestCapabilities);

        List<ServiceTarget> configured = targets.findByServiceIdAndEnabledTrueOrderByPriorityAsc(service.getId());
        boolean degradedAllowed = service.isAllowDegraded() || service.getFailoverPolicy() == FailoverPolicy.DEGRADED;
        String strictCompatibilityKey = service.getFailoverPolicy() == FailoverPolicy.STRICT
                ? referenceCompatibilityKey(configured, degradedAllowed) : null;
        boolean serviceHasLocalTargets = configured.stream()
                .map(target -> deployments.findById(target.getDeploymentId()).orElse(null))
                .anyMatch(deployment -> deployment != null && !deployment.isExternal());

        List<ResolvedTarget> local = new ArrayList<>();
        List<ResolvedTarget> external = new ArrayList<>();
        for (ServiceTarget target : configured) {
            if (target.isDegraded() && !degradedAllowed) continue;
            ModelDeployment deployment = deployments.findById(target.getDeploymentId()).orElse(null);
            if (deployment == null || !deployment.isEnabled()) continue;
            if (strictCompatibilityKey != null && !strictCompatibilityKey.equals(deployment.getCompatibilityKey())) continue;
            if (!deployment.isLoaded() || deployment.getHealthStatus() != HealthStatus.HEALTHY) continue;
            Set<String> availableCapabilities = new HashSet<>(readCapabilities(deployment.getCapabilitiesJson()));
            availableCapabilities.addAll(readCapabilities(deployment.getCapabilityOverridesJson()));
            if (!availableCapabilities.containsAll(requiredCapabilities)) continue;
            int limit = target.effectiveMaxConcurrency(deployment.getMaxConcurrency());
            if (limit > 0 && activeRequests.count(deployment.getId()) >= limit) continue;

            if (deployment.isExternal()) {
                if (projectId == null) continue;
                ExternalProvider provider = providers.findById(deployment.getExternalProviderId()).orElse(null);
                if (provider == null || !provider.isEnabled() || provider.getHealthStatus() != HealthStatus.HEALTHY) continue;
                boolean allowed = serviceHasLocalTargets
                        ? externalAccess.allowsAutoFailover(projectId, provider.getId())
                        : externalAccess.allowsManual(projectId, provider.getId());
                if (!allowed) continue;
                external.add(new ResolvedTarget(target, deployment, null, provider, limit,
                        serviceHasLocalTargets ? "AUTO_FAILOVER" : "MANUAL_EXTERNAL"));
                continue;
            }

            RuntimeEndpoint endpoint = endpoints.findById(deployment.getRuntimeEndpointId()).orElse(null);
            if (endpoint == null || !endpoint.isEnabled() || endpoint.getHealthStatus() != HealthStatus.HEALTHY) continue;
            local.add(new ResolvedTarget(target, deployment, endpoint, null, limit, "LOCAL"));
        }

        List<ResolvedTarget> ordered = new ArrayList<>(weightedSelector.order(local));
        // External targets are always appended after local targets. Their numeric priority
        // can never accidentally turn an opt-in failover target into the primary route.
        ordered.addAll(weightedSelector.order(external));
        return ordered;
    }

    public boolean acquire(ResolvedTarget target) { return activeRequests.tryAcquire(target.deployment().getId(), target.maxConcurrency()); }
    public void release(ResolvedTarget target) { activeRequests.release(target.deployment().getId()); }

    private String referenceCompatibilityKey(List<ServiceTarget> configured, boolean degradedAllowed) {
        for (ServiceTarget target : configured) {
            if (target.isDegraded() && !degradedAllowed) continue;
            ModelDeployment deployment = deployments.findById(target.getDeploymentId()).orElse(null);
            if (deployment != null && deployment.isEnabled()) return deployment.getCompatibilityKey();
        }
        return null;
    }

    private Set<String> readCapabilities(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try { return new HashSet<>(objectMapper.readValue(json, new TypeReference<List<String>>() { })); }
        catch (Exception exception) { return Set.of(); }
    }
}
