package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RequestStatus;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class SpendQuotaAdminController {
    private final OrganizationRepository organizations;
    private final SpendQuotaRepository quotas;
    private final ProjectRepository projects;
    private final TeamRepository teams;
    private final ApiKeyRepository apiKeys;
    private final LlmRequestRepository requests;

    public SpendQuotaAdminController(OrganizationRepository organizations, SpendQuotaRepository quotas,
                                     ProjectRepository projects, TeamRepository teams, ApiKeyRepository apiKeys,
                                     LlmRequestRepository requests) {
        this.organizations = organizations;
        this.quotas = quotas;
        this.projects = projects;
        this.teams = teams;
        this.apiKeys = apiKeys;
        this.requests = requests;
    }

    @GetMapping("/organizations/{organizationId}/quotas")
    @Transactional(readOnly = true)
    public List<QuotaView> list(@PathVariable UUID organizationId) {
        requireOrganization(organizationId);
        return quotas.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream().map(QuotaView::from).toList();
    }

    @PostMapping("/organizations/{organizationId}/quotas")
    @Transactional
    public QuotaView create(@PathVariable UUID organizationId, @Valid @RequestBody CreateQuota request) {
        requireOrganization(organizationId);
        SpendQuotaScope scope = request.scopeType() == null ? SpendQuotaScope.ORGANIZATION : request.scopeType();
        UUID scopeId = request.scopeId() == null && scope == SpendQuotaScope.ORGANIZATION ? organizationId : request.scopeId();
        validateScope(organizationId, scope, scopeId);
        SpendQuota quota = quotas.save(new SpendQuota(organizationId, scope, scopeId, request.name(),
                request.currency(), request.limitAmount(), request.period(), request.enabled() == null || request.enabled()));
        return QuotaView.from(quota);
    }

    @PatchMapping("/quotas/{quotaId}")
    @Transactional
    public QuotaView update(@PathVariable UUID quotaId, @Valid @RequestBody UpdateQuota request) {
        SpendQuota quota = requireQuota(quotaId);
        applyUpdate(quota, request);
        return QuotaView.from(quotas.save(quota));
    }

    /** Organization-scoped variant allows organization administrators to edit a quota. */
    @PatchMapping("/organizations/{organizationId}/quotas/{quotaId}")
    @Transactional
    public QuotaView updateInOrganization(@PathVariable UUID organizationId, @PathVariable UUID quotaId,
                                          @Valid @RequestBody UpdateQuota request) {
        SpendQuota quota = requireQuota(quotaId);
        if (!organizationId.equals(quota.getOrganizationId())) throw mismatch();
        applyUpdate(quota, request);
        return QuotaView.from(quotas.save(quota));
    }

    @DeleteMapping("/quotas/{quotaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID quotaId) { quotas.delete(requireQuota(quotaId)); }

    @DeleteMapping("/organizations/{organizationId}/quotas/{quotaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteInOrganization(@PathVariable UUID organizationId, @PathVariable UUID quotaId) {
        SpendQuota quota = requireQuota(quotaId);
        if (!organizationId.equals(quota.getOrganizationId())) throw mismatch();
        quotas.delete(quota);
    }

    @GetMapping("/organizations/{organizationId}/quota-overview")
    @Transactional(readOnly = true)
    public QuotaOverview overview(@PathVariable UUID organizationId,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to) {
        requireOrganization(organizationId);
        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (end.isBefore(start)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USAGE_RANGE",
                "The quota overview end date must not be before the start date.");
        Instant fromInstant = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant toExclusive = end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<Project> organizationProjects = projects.findByOrganizationId(organizationId);
        Map<UUID, Project> projectById = organizationProjects.stream().collect(Collectors.toMap(Project::getId, Function.identity()));
        List<UUID> projectIds = new ArrayList<>(projectById.keySet());
        List<LlmRequest> scopedRequests = projectIds.isEmpty() ? List.of() : requests
                .findByProjectIdInAndStartedAtAfter(projectIds, fromInstant.minusNanos(1)).stream()
                .filter(row -> row.getStartedAt() != null && row.getStartedAt().isBefore(toExclusive))
                .toList();
        Map<UUID, Team> teamById = teams.findByOrganizationIdOrderByNameAsc(organizationId).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        Map<UUID, ApiKey> keyById = projectIds.isEmpty() ? Map.of() : apiKeys.findByProjectIdIn(projectIds).stream()
                .collect(Collectors.toMap(ApiKey::getId, Function.identity(), (left, right) -> left));

        UsageBucket total = new UsageBucket();
        Map<LocalDate, UsageBucket> byDay = new TreeMap<>();
        Map<UUID, UsageBucket> projectUsage = new LinkedHashMap<>();
        Map<UUID, UsageBucket> teamUsage = new LinkedHashMap<>();
        Map<UUID, UsageBucket> keyUsage = new LinkedHashMap<>();
        for (LlmRequest row : scopedRequests) {
            total.add(row);
            byDay.computeIfAbsent(row.getStartedAt().atZone(ZoneOffset.UTC).toLocalDate(), ignored -> new UsageBucket()).add(row);
            projectUsage.computeIfAbsent(row.getProjectId(), ignored -> new UsageBucket()).add(row);
            Project project = projectById.get(row.getProjectId());
            if (project != null && project.getTeamId() != null && teamById.containsKey(project.getTeamId())) {
                teamUsage.computeIfAbsent(project.getTeamId(), ignored -> new UsageBucket()).add(row);
            }
            if (row.getApiKeyId() != null && keyById.containsKey(row.getApiKeyId())) {
                keyUsage.computeIfAbsent(row.getApiKeyId(), ignored -> new UsageBucket()).add(row);
            }
        }
        List<SeriesPoint> series = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            series.add(new SeriesPoint(day, byDay.getOrDefault(day, new UsageBucket()).view()));
        }
        List<SpendQuota> configuredQuotas = quotas.findByOrganizationIdOrderByCreatedAtAsc(organizationId);
        Instant billingStart = configuredQuotas.stream()
                .map(quota -> SpendQuotaService.periodStart(quota.getPeriod()))
                .min(Instant::compareTo)
                .orElse(fromInstant);
        Instant billingEndExclusive = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<LlmRequest> billingRequests = projectIds.isEmpty() ? List.of() : requests
                .findByProjectIdInAndStartedAtAfter(projectIds, billingStart.minusNanos(1)).stream()
                .filter(row -> row.getStartedAt() != null && row.getStartedAt().isBefore(billingEndExclusive))
                .toList();
        return new QuotaOverview(organizationId, start, end, total.view(), series,
                projectUsage.entrySet().stream().map(entry -> new DimensionUsage(entry.getKey(), projectById.get(entry.getKey()).getName(), entry.getValue().view())).toList(),
                teamUsage.entrySet().stream().map(entry -> new DimensionUsage(entry.getKey(), teamById.get(entry.getKey()).getName(), entry.getValue().view())).toList(),
                keyUsage.entrySet().stream().map(entry -> new DimensionUsage(entry.getKey(), keyLabel(keyById.get(entry.getKey())), entry.getValue().view())).toList(),
                configuredQuotas.stream().map(quota -> QuotaView.from(quota,
                        usageFor(quota, projectById, billingRequests), quotaScopeLabel(quota, projectById, teamById, keyById))).toList());
    }

    private UsageBucket usageFor(SpendQuota quota, Map<UUID, Project> projectById, List<LlmRequest> rows) {
        UsageBucket result = new UsageBucket();
        Instant periodStart = SpendQuotaService.periodStart(quota.getPeriod());
        Instant periodEndExclusive = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        for (LlmRequest row : rows) {
            Project project = projectById.get(row.getProjectId());
            if (project != null && row.getStartedAt() != null
                    && !row.getStartedAt().isBefore(periodStart)
                    && row.getStartedAt().isBefore(periodEndExclusive)
                    && quotaApplies(quota, project, row.getApiKeyId())) result.add(row);
        }
        return result;
    }

    private boolean quotaApplies(SpendQuota quota, Project project, UUID apiKeyId) {
        return switch (quota.getScopeType()) {
            case ORGANIZATION -> true;
            case TEAM -> project.getTeamId() != null && project.getTeamId().equals(quota.getScopeId());
            case PROJECT -> project.getId().equals(quota.getScopeId());
            case API_KEY -> apiKeyId != null && apiKeyId.equals(quota.getScopeId());
        };
    }
    private String keyLabel(ApiKey key) { return key.getName() + " (" + key.getKeyPrefix() + ")"; }

    private String quotaScopeLabel(SpendQuota quota, Map<UUID, Project> projectById,
                                   Map<UUID, Team> teamById, Map<UUID, ApiKey> keyById) {
        return switch (quota.getScopeType()) {
            case ORGANIZATION -> "Organization";
            case TEAM -> teamById.get(quota.getScopeId()) == null ? "Team" : teamById.get(quota.getScopeId()).getName();
            case PROJECT -> projectById.get(quota.getScopeId()) == null ? "Project" : projectById.get(quota.getScopeId()).getName();
            case API_KEY -> keyById.get(quota.getScopeId()) == null ? "API key" : keyLabel(keyById.get(quota.getScopeId()));
        };
    }

    private static LocalDate quotaPeriodFrom(SpendQuotaPeriod period) {
        return period == SpendQuotaPeriod.TOTAL ? null
                : SpendQuotaService.periodStart(period).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static LocalDate quotaPeriodTo() { return LocalDate.now(ZoneOffset.UTC); }

    private void applyUpdate(SpendQuota quota, UpdateQuota request) {
        SpendQuotaScope scope = request.scopeType() == null ? quota.getScopeType() : request.scopeType();
        UUID scopeId = request.scopeId() == null ? quota.getScopeId() : request.scopeId();
        validateScope(quota.getOrganizationId(), scope, scopeId);
        quota.configure(scope, scopeId, request.name(), request.currency(), request.limitAmount(), request.period(), request.enabled());
    }

    private SpendQuota requireQuota(UUID id) {
        return quotas.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "QUOTA_NOT_FOUND", "The quota does not exist."));
    }

    private void requireOrganization(UUID id) {
        if (!organizations.existsById(id)) throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist.");
    }

    private void validateScope(UUID organizationId, SpendQuotaScope scope, UUID scopeId) {
        if (scope == null || scopeId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA_SCOPE", "scopeType and scopeId are required.");
        boolean valid = switch (scope) {
            case ORGANIZATION -> organizationId.equals(scopeId);
            case TEAM -> teams.findById(scopeId).map(team -> organizationId.equals(team.getOrganizationId())).orElse(false);
            case PROJECT -> projects.findById(scopeId).map(project -> organizationId.equals(project.getOrganizationId())).orElse(false);
            case API_KEY -> apiKeys.findById(scopeId).flatMap(key -> projects.findById(key.getProjectId())).map(project -> organizationId.equals(project.getOrganizationId())).orElse(false);
        };
        if (!valid) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA_SCOPE", "The quota scope does not belong to the organization.");
    }

    private ApiException mismatch() { return new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "The quota belongs to another organization."); }

    public record CreateQuota(@NotBlank String name, @NotNull SpendQuotaScope scopeType, UUID scopeId,
                              @NotNull Currency currency, @NotNull @DecimalMin("0.0") BigDecimal limitAmount,
                              @NotNull SpendQuotaPeriod period, Boolean enabled) { }
    public record UpdateQuota(SpendQuotaScope scopeType, UUID scopeId, String name, Currency currency,
                              @DecimalMin("0.0") BigDecimal limitAmount, SpendQuotaPeriod period, Boolean enabled) { }
    public record QuotaView(UUID id, UUID organizationId, SpendQuotaScope scopeType, UUID scopeId, String name,
                            Currency currency, BigDecimal limitAmount, SpendQuotaPeriod period, boolean enabled,
                            Instant createdAt, Instant updatedAt, BigDecimal usedAmount, BigDecimal usagePercent,
                            boolean exceeded, String scopeLabel, LocalDate periodFrom, LocalDate periodTo) {
        static QuotaView from(SpendQuota quota) {
            return from(quota, new UsageBucket(), quota.getScopeType().name());
        }
        static QuotaView from(SpendQuota quota, UsageBucket usage, String scopeLabel) {
            BigDecimal used = usage.costByCurrency.getOrDefault(quota.getCurrency().name(), BigDecimal.ZERO);
            BigDecimal percent = quota.getLimitAmount().signum() == 0 ? BigDecimal.ZERO
                    : used.multiply(BigDecimal.valueOf(100)).divide(quota.getLimitAmount(), 4, java.math.RoundingMode.HALF_UP);
            boolean over = used.compareTo(quota.getLimitAmount()) >= 0;
            return new QuotaView(quota.getId(), quota.getOrganizationId(), quota.getScopeType(), quota.getScopeId(), quota.getName(),
                    quota.getCurrency(), quota.getLimitAmount(), quota.getPeriod(), quota.isEnabled(), quota.getCreatedAt(), quota.getUpdatedAt(),
                    used, percent, over, scopeLabel, quotaPeriodFrom(quota.getPeriod()), quotaPeriodTo());
        }
    }

    public record QuotaOverview(UUID organizationId, LocalDate from, LocalDate to, UsageView total,
                                List<SeriesPoint> series, List<DimensionUsage> byProject,
                                List<DimensionUsage> byTeam, List<DimensionUsage> byApiKey,
                                List<QuotaView> quotas) { }
    public record SeriesPoint(LocalDate date, BigDecimal amount, long requestCount, Currency currency, UsageView usage) {
        SeriesPoint(LocalDate date, UsageView usage) { this(date, usage.amount(), usage.requestCount(), usage.currency(), usage); }
    }
    public record DimensionUsage(UUID id, String name, String label, long requestCount, BigDecimal amount,
                                 Currency currency, long inputTokens, long outputTokens, UsageView usage) {
        DimensionUsage(UUID id, String name, UsageView usage) { this(id, name, name, usage.requestCount(), usage.amount(), usage.currency(), usage.inputTokens(), usage.outputTokens(), usage); }
    }
    public record UsageView(long requestCount, long succeeded, long failed, long inputTokens,
                            long outputTokens, Map<String, BigDecimal> costByCurrency, BigDecimal amount, Currency currency) { }
    private static final class UsageBucket {
        long requests;
        long succeeded;
        long failed;
        long inputTokens;
        long outputTokens;
        Map<String, BigDecimal> costByCurrency = new LinkedHashMap<>();
        void add(LlmRequest row) {
            requests++;
            if (row.getStatus() == RequestStatus.SUCCEEDED) succeeded++; else if (row.getStatus() == RequestStatus.FAILED) failed++;
            if (row.getInputTokens() != null) inputTokens += row.getInputTokens();
            if (row.getOutputTokens() != null) outputTokens += row.getOutputTokens();
            if (row.getEstimatedCost() != null) costByCurrency.merge(row.getCostCurrency() == null ? Currency.KRW.name() : row.getCostCurrency().name(), row.getEstimatedCost(), BigDecimal::add);
        }
        UsageView view() { return new UsageView(requests, succeeded, failed, inputTokens, outputTokens, costByCurrency, costByCurrency.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add), costByCurrency.containsKey(Currency.KRW.name()) ? Currency.KRW : Currency.USD); }
    }
}
