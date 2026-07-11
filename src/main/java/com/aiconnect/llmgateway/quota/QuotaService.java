package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class QuotaService {
    private final ApiKeyService apiKeyService;
    private final ProjectQuotaRepository quotas;
    private final LlmRequestRepository requests;
    private final ConcurrentHashMap<UUID, RateWindow> windows = new ConcurrentHashMap<>();
    public QuotaService(ApiKeyService apiKeyService, ProjectQuotaRepository quotas, LlmRequestRepository requests) {
        this.apiKeyService = apiKeyService; this.quotas = quotas; this.requests = requests;
    }
    public void check(String authorization, JsonNode request) {
        ApiKeyCredentials credentials = apiKeyService.authenticate(authorization);
        ProjectQuota quota = quotas.findById(credentials.project().getId()).orElse(null);
        int rpm = quota == null ? 60 : quota.getRequestsPerMinute();
        if (!windows.computeIfAbsent(credentials.apiKey().getId(), ignored -> new RateWindow()).tryAcquire(rpm, Instant.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "The API key has exceeded its requests-per-minute limit.");
        }
        if (quota != null && quota.getMonthlyTokenLimit() != null) {
            long used = tokensUsedThisMonth(credentials.project().getId());
            int requested = Math.max(Math.max(0, request.path("max_tokens").asInt(0)),
                    Math.max(0, request.path("max_completion_tokens").asInt(0)));
            if (used >= quota.getMonthlyTokenLimit() || used + requested > quota.getMonthlyTokenLimit()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOKEN_QUOTA_EXCEEDED", "The project would exceed its monthly token limit.");
            }
        }
    }
    private long tokensUsedThisMonth(UUID projectId) {
        Instant start = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return requests.sumTokensByProjectSince(projectId, start);
    }
}
