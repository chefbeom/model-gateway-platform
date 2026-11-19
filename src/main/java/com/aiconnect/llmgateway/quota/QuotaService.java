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

@Service
public class QuotaService {
    private final ApiKeyService apiKeyService;
    private final ProjectQuotaRepository quotas;
    private final LlmRequestRepository requests;
    private final RateLimitStore rateLimits;
    @org.springframework.beans.factory.annotation.Autowired
    private com.aiconnect.llmgateway.beta.BetaMemberRepository betaMembers;
    public QuotaService(ApiKeyService apiKeyService, ProjectQuotaRepository quotas, LlmRequestRepository requests, RateLimitStore rateLimits) {
        this.apiKeyService = apiKeyService; this.quotas = quotas; this.requests = requests; this.rateLimits = rateLimits;
    }
    public void check(String authorization, JsonNode request) {
        ApiKeyCredentials credentials = apiKeyService.authenticate(authorization);
        if (betaMembers != null) betaMembers.findByProjectId(credentials.project().getId()).ifPresent(member -> {
            if (!(rateLimits instanceof RedisRateLimitStore)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BETA_SHARED_LIMIT_UNAVAILABLE", "Beta requires shared rate limits.");
            try {
                Instant now=Instant.now();
                if (!rateLimits.tryAcquire(member.getId(),5,now) || !rateLimits.tryAcquire(member.getServiceId(),30,now))
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"BETA_RATE_LIMIT_EXCEEDED","Beta request rate exceeded.");
            } catch (ApiException exception) { throw exception; }
            catch (RuntimeException exception) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"BETA_SHARED_LIMIT_UNAVAILABLE","Beta shared rate limits are unavailable."); }
        });
        ProjectQuota quota = quotas.findById(credentials.project().getId()).orElse(null);
        int rpm = quota == null ? 60 : quota.getRequestsPerMinute();
        if (!rateLimits.tryAcquire(credentials.apiKey().getId(), rpm, Instant.now())) {
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
