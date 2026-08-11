package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

/** Enforces hard monetary ceilings before a request enters the gateway. */
@Service
public class SpendQuotaService {
    private final ApiKeyService apiKeys;
    private final SpendQuotaRepository quotas;
    private final SpendCostQuery costs;
    private final ProjectRepository projects;
    private final TeamRepository teams;
    private final ApiKeyRepository keyRepository;
    private final LlmServiceRepository services;

    public SpendQuotaService(ApiKeyService apiKeys, SpendQuotaRepository quotas, SpendCostQuery costs,
                             ProjectRepository projects, TeamRepository teams, ApiKeyRepository keyRepository,
                             LlmServiceRepository services) {
        this.apiKeys = apiKeys;
        this.quotas = quotas;
        this.costs = costs;
        this.projects = projects;
        this.teams = teams;
        this.keyRepository = keyRepository;
        this.services = services;
    }

    public void check(String authorization, JsonNode body) {
        ApiKeyCredentials credentials = apiKeys.authenticate(authorization);
        Project project = credentials.project();
        List<SpendQuota> applicable = quotas.findByOrganizationIdAndEnabledTrue(project.getOrganizationId()).stream()
                .filter(quota -> applies(quota, project, credentials.apiKey()))
                .toList();
        if (applicable.isEmpty()) return;

        LlmService service = body == null || !body.hasNonNull("model") ? null
                : services.findByOrganizationIdAndServiceKeyAndEnabledTrue(project.getOrganizationId(), body.get("model").asText()).orElse(null);
        Currency requestCurrency = service == null || service.getCurrency() == null ? Currency.KRW : service.getCurrency();
        BigDecimal requested = estimateRequestCost(body, service);
        for (SpendQuota quota : applicable) {
            if (quota.getCurrency() != requestCurrency) {
                // No implicit KRW/USD conversion is performed. The UI shows each currency independently.
                continue;
            }
            Instant from = periodStart(quota.getPeriod());
            BigDecimal used = quota.getScopeType() == SpendQuotaScope.API_KEY
                    ? costs.sumForApiKey(credentials.apiKey().getId(), quota.getCurrency(), from)
                    : costs.sumForProjects(scopeProjects(quota, project), quota.getCurrency(), from);
            if (used.compareTo(quota.getLimitAmount()) >= 0 || used.add(requested).compareTo(quota.getLimitAmount()) > 0) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED",
                        "The " + quota.getName() + " spend quota has been exceeded (" + quota.getCurrency()
                                + " " + used.stripTrailingZeros().toPlainString() + " / "
                                + quota.getLimitAmount().stripTrailingZeros().toPlainString() + ").");
            }
        }
    }

    private boolean applies(SpendQuota quota, Project project, ApiKey key) {
        return switch (quota.getScopeType()) {
            case ORGANIZATION -> project.getOrganizationId().equals(quota.getScopeId());
            case TEAM -> project.getTeamId() != null && project.getTeamId().equals(quota.getScopeId());
            case PROJECT -> project.getId().equals(quota.getScopeId());
            case API_KEY -> key.getId().equals(quota.getScopeId());
        };
    }

    private List<UUID> scopeProjects(SpendQuota quota, Project current) {
        return switch (quota.getScopeType()) {
            case ORGANIZATION -> projects.findByOrganizationId(quota.getScopeId()).stream().map(Project::getId).toList();
            case TEAM -> projects.findByTeamId(quota.getScopeId()).stream().map(Project::getId).toList();
            case PROJECT -> List.of(quota.getScopeId());
            case API_KEY -> List.of(current.getId()); // never used by the API-key branch
        };
    }

    private BigDecimal estimateRequestCost(JsonNode body, LlmService service) {
        if (service == null) return BigDecimal.ZERO;
        int maxOutput = body == null ? 0
                : Math.max(0, Math.max(body.path("max_completion_tokens").asInt(0), body.path("max_tokens").asInt(0)));
        int inputTokens = estimateInputTokens(body);
        BigDecimal inputCost = service.getInputPricePerMillion()
                .multiply(BigDecimal.valueOf(inputTokens)).movePointLeft(6);
        BigDecimal outputCost = service.getOutputPricePerMillion()
                .multiply(BigDecimal.valueOf(maxOutput)).movePointLeft(6);
        return inputCost.add(outputCost);
    }

    /**
     * Chat requests do not carry a token count before they are sent to the provider.
     * Reserve a conservative prompt estimate so a quota cannot be bypassed by omitting
     * max_tokens. The completed request is reconciled with the persisted usage record.
     */
    private int estimateInputTokens(JsonNode body) {
        JsonNode messages = body == null ? null : body.get("messages");
        if (messages == null || !messages.isArray()) return 0;
        return Math.max(0, (messages.toString().length() + 3) / 4);
    }
    static Instant periodStart(SpendQuotaPeriod period) {
        Instant now = Instant.now();
        return switch (period) {
            case DAILY -> LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            case MONTHLY -> YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            case TOTAL -> Instant.EPOCH;
        };
    }
}
