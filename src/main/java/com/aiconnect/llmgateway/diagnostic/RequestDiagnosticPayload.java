package com.aiconnect.llmgateway.diagnostic;

import java.util.List;
import java.util.UUID;

/** JSON contract returned only by authenticated request-detail APIs. */
public record RequestDiagnosticPayload(
        int schemaVersion,
        RequestProfile request,
        ServicePolicy service,
        String finalCode,
        Integer httpStatus,
        int attemptedCount,
        String summary,
        List<Target> targets,
        List<Recommendation> recommendations,
        Failure failure
) {
    public record RequestProfile(
            String logicalModel,
            String requestType,
            List<String> capabilities,
            boolean stream,
            int messageCount,
            int toolCount,
            boolean hasResponseFormat,
            String responseFormatType,
            boolean hasMaxTokens,
            boolean hasMaxCompletionTokens,
            int estimatedInputTokens,
            int requestedOutputTokens
    ) { }

    public record ServicePolicy(
            String failoverPolicy,
            String retryPolicy,
            boolean degradedAllowed,
            List<String> requiredCapabilities
    ) { }

    public record Target(
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

    public record Failure(String code, String message, String providerMessage, boolean failoverAllowed) { }

    public record Recommendation(String code, String title, String detail) { }
}
