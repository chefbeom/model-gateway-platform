package com.aiconnect.llmgateway.monitoring;

import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.RequestStatus;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AdminRequestExplorerService {
    private final AdminRequestQueryRepository requests;
    private final RequestAttemptQueryRepository attempts;
    public AdminRequestExplorerService(AdminRequestQueryRepository requests, RequestAttemptQueryRepository attempts) { this.requests = requests; this.attempts = attempts; }
    @Transactional(readOnly = true)
    public PageResult search(UUID organizationId, UUID projectId, UUID serviceId, UUID deploymentId, String status,
                             boolean failoverOnly, Instant from, Instant to, int page, int size) {
        RequestStatus parsedStatus = parseStatus(status);
        Page<LlmRequest> result = requests.search(organizationId, projectId, serviceId, deploymentId, parsedStatus, failoverOnly, from, to,
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size))));
        List<RequestView> items = result.getContent().stream().map(this::view).toList();
        return new PageResult(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
    private RequestView view(LlmRequest request) {
        List<AttemptView> attemptViews = attempts.findAttempts(request.getId()).stream().map(AttemptView::from).toList();
        return new RequestView(request.getRequestId(), request.getProjectId(), request.getApiKeyId(), request.getServiceId(), request.getFinalDeploymentId(),
                request.getStatus().name(), request.getInputTokens(), request.getOutputTokens(), request.getEstimatedCost(), request.getLatencyMs(),
                request.getFailoverCount(), request.getHttpStatus(), request.getErrorCode(), request.getStartedAt(), request.getCompletedAt(), attemptViews);
    }
    private RequestStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try { return RequestStatus.valueOf(status.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_STATUS", "Unknown request status filter."); }
    }
    public record PageResult(List<RequestView> items, int page, int size, long totalElements, int totalPages) { }
    public record RequestView(String requestId, UUID projectId, UUID apiKeyId, UUID serviceId, UUID finalDeploymentId, String status,
                              Integer inputTokens, Integer outputTokens, java.math.BigDecimal estimatedCost, Long latencyMs, int failoverCount,
                              Integer httpStatus, String errorCode, Instant startedAt, Instant completedAt, List<AttemptView> attempts) { }
    public record AttemptView(UUID deploymentId, int attemptNumber, String status, Instant startedAt, Instant completedAt, Long latencyMs,
                              Integer httpStatus, String errorType, String errorMessage, boolean responseStarted) {
        static AttemptView from(RequestAttemptQueryRepository.AttemptProjection item) { return new AttemptView(item.getDeploymentId(), item.getAttemptNumber(), item.getStatus(), item.getStartedAt(), item.getCompletedAt(), item.getLatencyMs(), item.getHttpStatus(), item.getErrorType(), item.getErrorMessage(), item.isResponseStarted()); }
    }
}
