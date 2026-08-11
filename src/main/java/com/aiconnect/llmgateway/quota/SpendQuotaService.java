package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.ServiceTarget;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.ServiceTargetRepository;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Enforces hard monetary ceilings before a request enters the gateway. */
@Service
public class SpendQuotaService {
    private static final int DEFAULT_OUTPUT_RESERVATION_TOKENS = 4096;
    private final ApiKeyService apiKeys;
    private final SpendQuotaRepository quotas;
    private final SpendCostQuery costs;
    private final ProjectRepository projects;
    private final TeamRepository teams;
    private final ApiKeyRepository keyRepository;
    private final LlmServiceRepository services;
    private final SpendQuotaReservationRepository reservations;
    private final ServiceTargetRepository targets;
    private final ModelDeploymentRepository deployments;

    public SpendQuotaService(ApiKeyService apiKeys, SpendQuotaRepository quotas, SpendCostQuery costs,
                             ProjectRepository projects, TeamRepository teams, ApiKeyRepository keyRepository,
                             LlmServiceRepository services, SpendQuotaReservationRepository reservations,
                             ServiceTargetRepository targets, ModelDeploymentRepository deployments) {
        this.apiKeys = apiKeys;
        this.quotas = quotas;
        this.costs = costs;
        this.projects = projects;
        this.teams = teams;
        this.keyRepository = keyRepository;
        this.services = services;
        this.reservations = reservations;
        this.targets = targets;
        this.deployments = deployments;
    }

    @Transactional
    public void check(String authorization, JsonNode body) {
        Reservation reservation = reserve(authorization, body);
        if (reservation != null) release(reservation);
    }

    /**
     * Atomically reserves the worst-case request cost against every applicable quota.
     * The reservation is released by the servlet filter after the gateway has completed.
     * Its expiry is also a crash-safety backstop for abandoned requests.
     */
    @Transactional
    public Reservation reserve(String authorization, JsonNode body) {
        ApiKeyCredentials credentials = apiKeys.authenticate(authorization);
        Project project = credentials.project();
        Instant now = Instant.now();
        reservations.deleteExpired(now);

        List<SpendQuota> applicable = quotas.findByOrganizationIdAndEnabledTrue(project.getOrganizationId()).stream()
                .filter(quota -> applies(quota, project, credentials.apiKey()))
                .sorted(Comparator.comparing(SpendQuota::getId))
                .toList();
        if (applicable.isEmpty()) return null;

        LlmService service = body == null || !body.hasNonNull("model") ? null
                : services.findByOrganizationIdAndServiceKeyAndEnabledTrue(
                        project.getOrganizationId(), body.get("model").asText()).orElse(null);
        Map<Currency, BigDecimal> requestedByCurrency = estimateRequestCosts(body, service);
        UUID reservationKey = UUID.randomUUID();
        List<SpendQuota> locked = new ArrayList<>();
        for (SpendQuota item : applicable) {
            SpendQuota quota = quotas.findByIdForUpdate(item.getId()).orElse(null);
            if (quota != null && quota.isEnabled() && applies(quota, project, credentials.apiKey())) {
                locked.add(quota);
            }
        }

        Instant expiresAt = now.plusSeconds(3600);
        List<SpendQuotaReservation> created = new ArrayList<>();
        for (SpendQuota quota : locked) {
            BigDecimal requested = requestedByCurrency.getOrDefault(quota.getCurrency(), BigDecimal.ZERO);
            if (requested.signum() <= 0) continue;

            Instant from = periodStart(quota.getPeriod());
            BigDecimal committed = quota.getScopeType() == SpendQuotaScope.API_KEY
                    ? costs.sumForApiKey(credentials.apiKey().getId(), quota.getCurrency(), from)
                    : costs.sumForProjects(scopeProjects(quota, project), quota.getCurrency(), from);
            BigDecimal active = reservations.sumActiveAmountByQuotaId(quota.getId(), now);
            BigDecimal used = committed.add(active == null ? BigDecimal.ZERO : active);
            if (used.compareTo(quota.getLimitAmount()) >= 0
                    || used.add(requested).compareTo(quota.getLimitAmount()) > 0) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED",
                        "The " + quota.getName() + " spend quota has been exceeded (" + quota.getCurrency()
                                + " " + used.stripTrailingZeros().toPlainString() + " / "
                                + quota.getLimitAmount().stripTrailingZeros().toPlainString() + ").");
            }
            created.add(new SpendQuotaReservation(
                    quota.getId(), reservationKey, requested, quota.getCurrency(), expiresAt));
        }
        if (!created.isEmpty()) reservations.saveAll(created);
        return new Reservation(reservationKey);
    }

    @Transactional
    public void release(Reservation reservation) {
        if (reservation != null && reservation.key() != null) {
            reservations.deleteByReservationKey(reservation.key());
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

    private Map<Currency, BigDecimal> estimateRequestCosts(JsonNode body, LlmService service) {
        Map<Currency, BigDecimal> estimates = new EnumMap<>(Currency.class);
        if (service == null) return estimates;

        int inputTokens = estimateInputTokens(body);
        int maxOutput = body == null
                ? 0
                : Math.max(0, Math.max(body.path("max_completion_tokens").asInt(0),
                        body.path("max_tokens").asInt(0)));
        if (maxOutput == 0) maxOutput = DEFAULT_OUTPUT_RESERVATION_TOKENS;
        addMax(estimates, service.getCurrency(),
                quote(service.getInputPricePerMillion(), service.getOutputPricePerMillion(), inputTokens, maxOutput));

        List<ServiceTarget> serviceTargets =
                targets.findByServiceIdAndEnabledTrueOrderByPriorityAsc(service.getId());
        Map<UUID, ModelDeployment> deploymentById = new HashMap<>();
        for (ModelDeployment deployment : deployments.findAllById(
                serviceTargets.stream().map(ServiceTarget::getDeploymentId).toList())) {
            deploymentById.put(deployment.getId(), deployment);
        }
        for (ServiceTarget target : serviceTargets) {
            ModelDeployment deployment = deploymentById.get(target.getDeploymentId());
            if (deployment == null || !deployment.isExternal()
                    || deployment.getProviderInputPricePerMillion() == null
                    || deployment.getProviderOutputPricePerMillion() == null) {
                continue;
            }
            addMax(estimates, deployment.getProviderPriceCurrency(), quote(
                    deployment.getProviderInputPricePerMillion(),
                    deployment.getProviderOutputPricePerMillion(), inputTokens, maxOutput));
        }
        return estimates;
    }

    private BigDecimal quote(BigDecimal inputPrice, BigDecimal outputPrice, int inputTokens, int outputTokens) {
        return inputPrice.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens))).movePointLeft(6);
    }

    private void addMax(Map<Currency, BigDecimal> estimates, Currency currency, BigDecimal amount) {
        if (amount == null || amount.signum() < 0) return;
        estimates.merge(currency == null ? Currency.KRW : currency, amount, BigDecimal::max);
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
    public record Reservation(UUID key) { }
    static Instant periodStart(SpendQuotaPeriod period) {
        Instant now = Instant.now();
        return switch (period) {
            case DAILY -> LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            case MONTHLY -> YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            case TOTAL -> Instant.EPOCH;
        };
    }
}
