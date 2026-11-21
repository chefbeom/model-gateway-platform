package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Project-scoped spend quota management under immutable administrator ceilings. */
@RestController
@RequestMapping("/api/admin/projects/{projectId}/spend-quotas")
public class ProjectSpendQuotaController {
    private final ProjectRepository projects;
    private final SpendQuotaRepository quotas;
    private final ApiKeyRepository apiKeys;
    private final TeamRepository teams;
    private final SpendCostQuery costs;
    private final TeamAccessService access;

    public ProjectSpendQuotaController(ProjectRepository projects,
                                       SpendQuotaRepository quotas,
                                       ApiKeyRepository apiKeys,
                                       TeamRepository teams,
                                       SpendCostQuery costs,
                                       TeamAccessService access) {
        this.projects = projects;
        this.quotas = quotas;
        this.apiKeys = apiKeys;
        this.teams = teams;
        this.costs = costs;
        this.access = access;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ProjectSpendQuotasView list(@PathVariable UUID projectId, HttpServletRequest http) {
        Project project = requireProject(projectId);
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        boolean organizationAdmin = isPlatformOrOrgAdmin(http, project.getOrganizationId());
        if (!organizationAdmin && !access.canManageProject(actor, projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_MANAGER_REQUIRED", "Project management permission is required.");
        }
        List<SpendQuota> orgQuotas = quotas.findByOrganizationIdOrderByCreatedAtAsc(project.getOrganizationId());
        Map<UUID, ApiKey> projectKeyMap = apiKeys.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(ApiKey::getId, key -> key, (a, b) -> a));

        List<SpendQuotaAdminController.QuotaView> ceilings = new ArrayList<>();
        List<SpendQuotaAdminController.QuotaView> projectQuotas = new ArrayList<>();
        for (SpendQuota quota : orgQuotas) {
            boolean appliesToProject = appliesToProject(quota, project, projectKeyMap.keySet());
            if (appliesToProject && quota.isEnabled() && quota.isAdminCeiling()) {
                SpendQuotaAdminController.QuotaView view = toView(quota, project, projectKeyMap);
                if (!organizationAdmin && (quota.getScopeType() == SpendQuotaScope.ORGANIZATION || quota.getScopeType() == SpendQuotaScope.TEAM)) {
                    view = new SpendQuotaAdminController.QuotaView(view.id(), view.organizationId(), view.scopeType(), view.scopeId(), view.name(), view.currency(), view.limitAmount(), view.period(), view.enabled(), view.adminCeiling(), view.createdAt(), view.updatedAt(), null, null, null, view.scopeLabel(), view.periodFrom(), view.periodTo());
                }
                ceilings.add(view);
            }
            if ((quota.getScopeType() == SpendQuotaScope.PROJECT && projectId.equals(quota.getScopeId()))
                    || (quota.getScopeType() == SpendQuotaScope.API_KEY && projectKeyMap.containsKey(quota.getScopeId()))) {
                projectQuotas.add(toView(quota, project, projectKeyMap));
            }
        }
        return new ProjectSpendQuotasView(projectId, ceilings, projectQuotas);
    }

    @PostMapping
    @Transactional
    public SpendQuotaAdminController.QuotaView create(@PathVariable UUID projectId,
                                                      @Valid @RequestBody CreateProjectSpendQuota request) {
        Project project = requireProject(projectId);
        UUID scopeId = resolveAndValidateScope(projectId, request.scopeType(), request.scopeId());
        enforceCeiling(project, request.scopeType(), scopeId, request.currency(), request.period(), request.limitAmount());
        boolean enabled = request.enabled() == null || request.enabled();
        SpendQuota saved = quotas.save(new SpendQuota(
                project.getOrganizationId(),
                request.scopeType(),
                scopeId,
                request.name().trim(),
                request.currency(),
                request.limitAmount(),
                request.period(),
                enabled,
                false));
        Map<UUID, ApiKey> projectKeyMap = apiKeys.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(ApiKey::getId, key -> key, (a, b) -> a));
        return toView(saved, project, projectKeyMap);
    }

    @PatchMapping("/{quotaId}")
    @Transactional
    public SpendQuotaAdminController.QuotaView update(@PathVariable UUID projectId,
                                                      @PathVariable UUID quotaId,
                                                      @Valid @RequestBody UpdateProjectSpendQuota request,
                                                      HttpServletRequest http) {
        Project project = requireProject(projectId);
        SpendQuota quota = requireProjectScopedQuota(projectId, quotaId);
        if (quota.isAdminCeiling() && !isPlatformOrOrgAdmin(http, project.getOrganizationId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_CEILING_IMMUTABLE",
                    "Administrator spend quota ceilings cannot be modified by project directors.");
        }
        BigDecimal nextLimit = request.limitAmount() != null ? request.limitAmount() : quota.getLimitAmount();
        if (!quota.isAdminCeiling()) {
            enforceCeiling(project, quota.getScopeType(), quota.getScopeId(), quota.getCurrency(), quota.getPeriod(), nextLimit);
        }
        quota.configure(null, null, request.name(), null, request.limitAmount(), null, request.enabled());
        SpendQuota saved = quotas.save(quota);
        Map<UUID, ApiKey> projectKeyMap = apiKeys.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(ApiKey::getId, key -> key, (a, b) -> a));
        return toView(saved, project, projectKeyMap);
    }

    @DeleteMapping("/{quotaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID projectId, @PathVariable UUID quotaId, HttpServletRequest http) {
        Project project = requireProject(projectId);
        SpendQuota quota = requireProjectScopedQuota(projectId, quotaId);
        if (quota.isAdminCeiling() && !isPlatformOrOrgAdmin(http, project.getOrganizationId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_CEILING_IMMUTABLE",
                    "Administrator spend quota ceilings cannot be deleted by project directors.");
        }
        quotas.delete(quota);
    }

    private void enforceCeiling(Project project, SpendQuotaScope scopeType, UUID scopeId,
                                Currency currency, SpendQuotaPeriod period, BigDecimal limitAmount) {
        List<SpendQuota> orgQuotas = quotas.findByOrganizationIdAndEnabledTrue(project.getOrganizationId());
        BigDecimal minAdminCeiling = null;
        BigDecimal minProjectLimit = null;
        for (SpendQuota candidate : orgQuotas) {
            if (candidate.getCurrency() != currency || candidate.getPeriod() != period) continue;
            if (candidate.isAdminCeiling() && coversTarget(candidate, project, scopeType, scopeId)) {
                minAdminCeiling = minAdminCeiling == null
                        ? candidate.getLimitAmount()
                        : minAdminCeiling.min(candidate.getLimitAmount());
            }
            if (scopeType == SpendQuotaScope.API_KEY && !candidate.isAdminCeiling()
                    && candidate.getScopeType() == SpendQuotaScope.PROJECT
                    && project.getId().equals(candidate.getScopeId())) {
                minProjectLimit = minProjectLimit == null
                        ? candidate.getLimitAmount()
                        : minProjectLimit.min(candidate.getLimitAmount());
            }
        }
        if (minAdminCeiling != null && limitAmount.compareTo(minAdminCeiling) > 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SPEND_QUOTA_CEILING_EXCEEDED",
                    "The spend quota limit (" + currency + " " + limitAmount.stripTrailingZeros().toPlainString()
                            + ") exceeds the administrator ceiling (" + currency + " "
                            + minAdminCeiling.stripTrailingZeros().toPlainString() + ").");
        }
        if (minProjectLimit != null && limitAmount.compareTo(minProjectLimit) > 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SPEND_QUOTA_CEILING_EXCEEDED",
                    "The API key spend quota limit (" + currency + " " + limitAmount.stripTrailingZeros().toPlainString()
                            + ") exceeds the project spend quota (" + currency + " "
                            + minProjectLimit.stripTrailingZeros().toPlainString() + ").");
        }
    }

    private boolean coversTarget(SpendQuota ceiling, Project project, SpendQuotaScope targetScope, UUID targetScopeId) {
        return switch (ceiling.getScopeType()) {
            case ORGANIZATION -> project.getOrganizationId().equals(ceiling.getScopeId());
            case TEAM -> project.getTeamId() != null && project.getTeamId().equals(ceiling.getScopeId());
            case PROJECT -> project.getId().equals(ceiling.getScopeId());
            case API_KEY -> targetScope == SpendQuotaScope.API_KEY && targetScopeId.equals(ceiling.getScopeId());
        };
    }

    private boolean appliesToProject(SpendQuota quota, Project project, Set<UUID> projectKeyIds) {
        return switch (quota.getScopeType()) {
            case ORGANIZATION -> project.getOrganizationId().equals(quota.getScopeId());
            case TEAM -> project.getTeamId() != null && project.getTeamId().equals(quota.getScopeId());
            case PROJECT -> project.getId().equals(quota.getScopeId());
            case API_KEY -> projectKeyIds.contains(quota.getScopeId());
        };
    }

    private UUID resolveAndValidateScope(UUID projectId, SpendQuotaScope scopeType, UUID scopeId) {
        if (scopeType == SpendQuotaScope.PROJECT) {
            UUID resolved = scopeId == null ? projectId : scopeId;
            if (!projectId.equals(resolved)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA_SCOPE",
                        "The quota scopeId must match the target projectId.");
            }
            return resolved;
        }
        if (scopeType == SpendQuotaScope.API_KEY) {
            if (scopeId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA_SCOPE",
                        "An API key scopeId is required for API_KEY spend quotas.");
            }
            ApiKey key = apiKeys.findById(scopeId)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA_SCOPE",
                            "The specified API key does not exist."));
            if (!projectId.equals(key.getProjectId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA_SCOPE",
                        "The specified API key does not belong to this project.");
            }
            return scopeId;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUOTA_SCOPE",
                "Project spend quotas only support PROJECT or API_KEY scope.");
    }

    private SpendQuota requireProjectScopedQuota(UUID projectId, UUID quotaId) {
        SpendQuota quota = quotas.findById(quotaId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "QUOTA_NOT_FOUND", "The quota does not exist."));
        boolean belongs = switch (quota.getScopeType()) {
            case PROJECT -> projectId.equals(quota.getScopeId());
            case API_KEY -> apiKeys.findById(quota.getScopeId()).map(key -> projectId.equals(key.getProjectId())).orElse(false);
            default -> false;
        };
        if (!belongs) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED",
                    "The quota does not belong to this project.");
        }
        return quota;
    }

    private SpendQuotaAdminController.QuotaView toView(SpendQuota quota, Project project, Map<UUID, ApiKey> projectKeyMap) {
        Instant from = SpendQuotaService.periodStart(quota.getPeriod());
        BigDecimal used = switch (quota.getScopeType()) {
            case ORGANIZATION -> costs.sumForProjects(
                    projects.findByOrganizationId(quota.getScopeId()).stream().map(Project::getId).toList(),
                    quota.getCurrency(), from);
            case TEAM -> costs.sumForProjects(
                    projects.findByTeamId(quota.getScopeId()).stream().map(Project::getId).toList(),
                    quota.getCurrency(), from);
            case PROJECT -> costs.sumForProjects(List.of(project.getId()), quota.getCurrency(), from);
            case API_KEY -> costs.sumForApiKey(quota.getScopeId(), quota.getCurrency(), from);
        };
        String scopeLabel = switch (quota.getScopeType()) {
            case ORGANIZATION -> "Organization";
            case TEAM -> teams.findById(quota.getScopeId()).map(Team::getName).orElse("Team");
            case PROJECT -> project.getName();
            case API_KEY -> {
                ApiKey key = projectKeyMap.get(quota.getScopeId());
                yield key == null ? "API key" : key.getName() + " (" + key.getKeyPrefix() + ")";
            }
        };
        return SpendQuotaAdminController.QuotaView.from(quota, used, scopeLabel);
    }

    private Project requireProject(UUID projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
    }

    private boolean isPlatformOrOrgAdmin(HttpServletRequest http, UUID organizationId) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin")) || (actor != null && actor.platformAdmin())) {
            return true;
        }
        return access.isOrganizationAdmin(actor, organizationId);
    }

    public record ProjectSpendQuotasView(UUID projectId,
                                         List<SpendQuotaAdminController.QuotaView> ceilings,
                                         List<SpendQuotaAdminController.QuotaView> quotas) { }

    public record CreateProjectSpendQuota(@NotNull SpendQuotaScope scopeType,
                                          UUID scopeId,
                                          @NotBlank String name,
                                          @NotNull SpendQuotaPeriod period,
                                          @NotNull Currency currency,
                                          @NotNull @DecimalMin("0.0") BigDecimal limitAmount,
                                          Boolean enabled) { }

    public record UpdateProjectSpendQuota(String name,
                                          @DecimalMin("0.0") BigDecimal limitAmount,
                                          Boolean enabled) { }
}
