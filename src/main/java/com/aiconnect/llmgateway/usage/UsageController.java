package com.aiconnect.llmgateway.usage;

import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RequestStatus;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/me")
public class UsageController {
    private final ApiKeyService apiKeyService;
    private final LlmRequestRepository requests;
    private final LlmServiceRepository services;
    private final ModelDeploymentRepository deployments;

    public UsageController(ApiKeyService apiKeyService, LlmRequestRepository requests,
                           LlmServiceRepository services, ModelDeploymentRepository deployments) {
        this.apiKeyService = apiKeyService; this.requests = requests; this.services = services; this.deployments = deployments;
    }
    @GetMapping("/usage")
    public UsageSummary usage(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                              @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        ApiKeyCredentials credentials = apiKeyService.authenticate(authorization);
        LocalDate startDate = from == null ? YearMonth.now(ZoneOffset.UTC).atDay(1) : from;
        LocalDate endExclusiveDate = to == null ? LocalDate.now(ZoneOffset.UTC).plusDays(1) : to.plusDays(1);
        if (!endExclusiveDate.isAfter(startDate)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USAGE_RANGE", "The usage end date must not be before the start date.");
        Instant start = startDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endExclusive = endExclusiveDate.atStartOfDay().toInstant(ZoneOffset.UTC);
        List<LlmRequest> all = requests.findByProjectIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                credentials.project().getId(), start, endExclusive);
        int input = all.stream().map(LlmRequest::getInputTokens).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        int output = all.stream().map(LlmRequest::getOutputTokens).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        BigDecimal cost = all.stream().map(LlmRequest::getEstimatedCost).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        long failed = all.stream().filter(request -> request.getStatus() == RequestStatus.FAILED).count();
        return new UsageSummary(all.size(), input, output, cost, failed, startDate, endExclusiveDate.minusDays(1));
    }
    @GetMapping("/requests")
    public List<RequestView> requestHistory(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        ApiKeyCredentials credentials = apiKeyService.authenticate(authorization);
        return requests.findTop50ByProjectIdOrderByStartedAtDesc(credentials.project().getId()).stream().map(this::view).toList();
    }
    private RequestView view(LlmRequest request) {
        LlmService service = services.findById(request.getServiceId()).orElse(null);
        ModelDeployment deployment = request.getFinalDeploymentId() == null ? null : deployments.findById(request.getFinalDeploymentId()).orElse(null);
        return new RequestView(request.getRequestId(), service == null ? null : service.getServiceKey(),
                service == null ? null : service.getDisplayName(), deployment == null ? null : deployment.getDisplayName(),
                request.isStream(), request.getStatus().name(), request.getInputTokens(), request.getOutputTokens(),
                request.getEstimatedCost(), request.getLatencyMs(), request.getFailoverCount(), request.getHttpStatus(),
                request.getErrorCode(), request.getStartedAt(), request.getCompletedAt());
    }
    public record UsageSummary(int requestCount, int inputTokens, int outputTokens, BigDecimal estimatedCost, long failedRequests, LocalDate periodFrom, LocalDate periodTo) { }
    public record RequestView(String requestId, String serviceKey, String serviceDisplayName, String deploymentDisplayName,
                              boolean stream, String status, Integer inputTokens, Integer outputTokens,
                              BigDecimal estimatedCost, Long latencyMs, int failoverCount, Integer httpStatus,
                              String errorCode, Instant startedAt, Instant completedAt) { }
}
