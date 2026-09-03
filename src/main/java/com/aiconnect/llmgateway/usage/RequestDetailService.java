package com.aiconnect.llmgateway.usage;

import com.aiconnect.llmgateway.diagnostic.RequestDiagnosticPayload;
import com.aiconnect.llmgateway.diagnostic.RequestDiagnosticService;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.monitoring.RequestAttemptQueryRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Builds a safe request detail view.
 *
 * <p>This view intentionally contains metadata only. Request and response content
 * are never part of this DTO, even when a project has encrypted content retention
 * enabled.</p>
 */
@Service
public class RequestDetailService {
    private final LlmServiceRepository services;
    private final ModelDeploymentRepository deployments;
    private final RequestAttemptQueryRepository attempts;

    @org.springframework.beans.factory.annotation.Autowired
    private RequestDiagnosticService diagnostics;

    public RequestDetailService(LlmServiceRepository services,
                                ModelDeploymentRepository deployments,
                                RequestAttemptQueryRepository attempts) {
        this.services = services;
        this.deployments = deployments;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public RequestDetail view(LlmRequest request) {
        LlmService service = services.findById(request.getServiceId()).orElse(null);
        ModelDeployment deployment = request.getFinalDeploymentId() == null
                ? null : deployments.findById(request.getFinalDeploymentId()).orElse(null);

        List<Attempt> attemptViews = attempts.findAttempts(request.getId()).stream()
                .map(item -> new Attempt(item.getDeploymentId(), item.getAttemptNumber(), item.getStatus(),
                        item.getStartedAt(), item.getCompletedAt(), item.getLatencyMs(), item.getHttpStatus(),
                        item.getErrorType(), item.isResponseStarted()))
                .toList();
        RequestDiagnosticPayload diagnostic = diagnostics == null ? null : diagnostics.view(request.getId()).orElse(null);

        String requestType = normalizeType(request.getRequestType());
        return new RequestDetail(
                request.getRequestId(),
                request.getProjectId(),
                request.getServiceId(),
                service == null ? null : service.getServiceKey(),
                service == null ? null : service.getDisplayName(),
                requestType,
                capabilitiesFor(requestType),
                request.getEndpoint(),
                request.isStream(),
                request.getStatus().name(),
                request.getFinalDeploymentId(),
                deployment == null ? null : deployment.getDisplayName(),
                request.getFinalProviderType(),
                request.getRoutingReason(),
                request.getInputTokens(),
                request.getOutputTokens(),
                request.getEstimatedCost(),
                request.getCostCurrency(),
                request.getInputUnitPrice(),
                request.getOutputUnitPrice(),
                request.getLatencyMs(),
                request.getFailoverCount(),
                request.getHttpStatus(),
                request.getErrorCode(),
                request.getStartedAt(),
                request.getCompletedAt(),
                attemptViews,
                diagnostic,
                request.getDataProtectionMode(),
                request.getDataProtectionLevel(),
                request.getDataProtectionAction(),
                request.getDataClassificationsJson(),
                request.getDataExternalAllowed()
        );
    }

    private String normalizeType(String value) {
        if (value == null || value.isBlank() || "CHAT_COMPLETION".equalsIgnoreCase(value)) return "TEXT_CHAT";
        return value.toUpperCase(Locale.ROOT);
    }

    private List<String> capabilitiesFor(String requestType) {
        List<String> capabilities = java.util.Arrays.stream(requestType.split("\\+"))
                .filter(type -> type.equals("VISION") || type.equals("TOOL_CALLING") || type.equals("STRUCTURED_OUTPUT"))
                .toList();
        return capabilities.isEmpty() ? List.of("TEXT") : capabilities;
    }

    public record RequestDetail(
            String requestId,
            java.util.UUID projectId,
            java.util.UUID serviceId,
            String serviceKey,
            String serviceDisplayName,
            String requestType,
            List<String> capabilities,
            String endpoint,
            boolean stream,
            String status,
            java.util.UUID finalDeploymentId,
            String deploymentDisplayName,
            String providerType,
            String routingReason,
            Integer inputTokens,
            Integer outputTokens,
            BigDecimal estimatedCost,
            Currency costCurrency,
            BigDecimal inputUnitPrice,
            BigDecimal outputUnitPrice,
            Long latencyMs,
            int failoverCount,
            Integer httpStatus,
            String errorCode,
            Instant startedAt,
            Instant completedAt,
            List<Attempt> attempts,
            RequestDiagnosticPayload diagnostic,
            String dataProtectionMode,
            String dataProtectionLevel,
            String dataProtectionAction,
            String dataClassificationsJson,
            Boolean dataExternalAllowed
    ) { }

    public record Attempt(
            java.util.UUID deploymentId,
            int attemptNumber,
            String status,
            Instant startedAt,
            Instant completedAt,
            Long latencyMs,
            Integer httpStatus,
            String errorType,
            boolean responseStarted
    ) { }
}