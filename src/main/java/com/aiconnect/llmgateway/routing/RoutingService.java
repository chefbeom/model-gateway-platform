package com.aiconnect.llmgateway.routing;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.repository.ServiceTargetRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class RoutingService {
    private final ServiceTargetRepository targets;
    private final ModelDeploymentRepository deployments;
    private final RuntimeEndpointRepository endpoints;
    private final ActiveRequestRegistry activeRequests;
    private final WeightedTargetSelector weightedSelector;
    private final ObjectMapper objectMapper;

    public RoutingService(ServiceTargetRepository targets, ModelDeploymentRepository deployments, RuntimeEndpointRepository endpoints,
                          ActiveRequestRegistry activeRequests, WeightedTargetSelector weightedSelector, ObjectMapper objectMapper) {
        this.targets = targets; this.deployments = deployments; this.endpoints = endpoints; this.activeRequests = activeRequests;
        this.weightedSelector = weightedSelector; this.objectMapper = objectMapper;
    }

    public List<ResolvedTarget> candidates(LlmService service, Set<String> requestCapabilities) {
        Set<String> requiredCapabilities = new HashSet<>(readCapabilities(service.getRequiredCapabilitiesJson()));
        requiredCapabilities.addAll(requestCapabilities);

        List<ServiceTarget> configured = targets.findByServiceIdAndEnabledTrueOrderByPriorityAsc(service.getId());
        boolean degradedAllowed = service.isAllowDegraded() || service.getFailoverPolicy() == FailoverPolicy.DEGRADED;
        String strictCompatibilityKey = service.getFailoverPolicy() == FailoverPolicy.STRICT
                ? referenceCompatibilityKey(configured, degradedAllowed)
                : null;

        List<ResolvedTarget> candidates = new ArrayList<>();
        for (ServiceTarget target : configured) {
            if (target.isDegraded() && !degradedAllowed) continue;
            ModelDeployment deployment = deployments.findById(target.getDeploymentId()).orElse(null);
            if (deployment == null || !deployment.isEnabled()) continue;
            if (strictCompatibilityKey != null && !strictCompatibilityKey.equals(deployment.getCompatibilityKey())) continue;
            if (!deployment.isLoaded() || deployment.getHealthStatus() != HealthStatus.HEALTHY) continue;
            RuntimeEndpoint endpoint = endpoints.findById(deployment.getRuntimeEndpointId()).orElse(null);
            if (endpoint == null || !endpoint.isEnabled() || endpoint.getHealthStatus() != HealthStatus.HEALTHY) continue;
            Set<String> availableCapabilities = new HashSet<>(readCapabilities(deployment.getCapabilitiesJson()));
            availableCapabilities.addAll(readCapabilities(deployment.getCapabilityOverridesJson()));
            if (!availableCapabilities.containsAll(requiredCapabilities)) continue;
            int limit = target.effectiveMaxConcurrency(deployment.getMaxConcurrency());
            if (limit > 0 && activeRequests.count(deployment.getId()) < limit) {
                candidates.add(new ResolvedTarget(target, deployment, endpoint, limit));
            }
        }
        return weightedSelector.order(candidates);
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
