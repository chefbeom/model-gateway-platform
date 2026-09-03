package com.aiconnect.llmgateway.routing;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Immutable routing evaluation captured at one point in time. */
public record RoutingDecision(
        List<ResolvedTarget> eligibleTargets,
        Set<String> requiredCapabilities,
        boolean degradedAllowed,
        String failoverPolicy,
        String retryPolicy,
        List<TargetEvaluation> evaluations
) {
    public boolean hasEligibleTargets() { return !eligibleTargets.isEmpty(); }

    public record TargetEvaluation(
            UUID targetId,
            UUID deploymentId,
            String displayName,
            String providerType,
            int priority,
            int weight,
            boolean targetEnabled,
            boolean degraded,
            boolean deploymentEnabled,
            boolean loaded,
            String deploymentHealth,
            String endpointDisplayName,
            String providerDisplayName,
            Integer activeRequests,
            int maxConcurrency,
            List<String> requiredCapabilities,
            List<String> availableCapabilities,
            List<String> missingCapabilities,
            boolean eligible,
            List<String> reasonCodes
    ) { }
}
