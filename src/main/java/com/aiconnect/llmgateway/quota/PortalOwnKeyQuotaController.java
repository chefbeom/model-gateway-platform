package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/portal/organizations/{organizationId}/usage-overview")
public class PortalOwnKeyQuotaController {
    private final ApiKeyRepository keys;
    private final ProjectRepository projects;
    private final SpendQuotaRepository quotas;
    private final SpendCostQuery costs;
    private final TeamAccessService access;

    public PortalOwnKeyQuotaController(ApiKeyRepository keys, ProjectRepository projects, SpendQuotaRepository quotas,
                                       SpendCostQuery costs, TeamAccessService access) {
        this.keys = keys; this.projects = projects; this.quotas = quotas; this.costs = costs; this.access = access;
    }

    @GetMapping("/own-key-quotas")
    @Transactional(readOnly = true)
    public List<OwnKeyQuota> list(@PathVariable UUID organizationId, @RequestParam(required = false) UUID projectId) {
        AuthPrincipal actor = CurrentActor.principal().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "PORTAL_AUTH_REQUIRED", "An authenticated session is required."));
        if (!access.canViewOrganization(actor, organizationId)) throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_SCOPE_REQUIRED", "Organization membership is required.");
        if (projectId != null && (projects.findById(projectId).filter(p -> organizationId.equals(p.getOrganizationId())).isEmpty() || !access.canViewProject(actor, projectId)))
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_USAGE_ACCESS_DENIED", "Project access is required.");
        Map<UUID, ApiKey> owned = keys.findByIssuedByUserId(actor.userId()).stream()
                .filter(key -> projectId == null || projectId.equals(key.getProjectId()))
                .filter(key -> projects.findById(key.getProjectId()).filter(p -> organizationId.equals(p.getOrganizationId())).isPresent())
                .filter(key -> access.canViewProject(actor, key.getProjectId()))
                .collect(Collectors.toMap(ApiKey::getId, key -> key));
        return quotas.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
                .filter(q -> q.getScopeType() == SpendQuotaScope.API_KEY && owned.containsKey(q.getScopeId()))
                .map(q -> new OwnKeyQuota(owned.get(q.getScopeId()).getName(), q.getName(), q.getCurrency(), q.getLimitAmount(), q.getPeriod(), q.isEnabled(), costs.sumForApiKey(q.getScopeId(), q.getCurrency(), SpendQuotaService.periodStart(q.getPeriod()))))
                .toList();
    }

    public record OwnKeyQuota(String keyName, String name, Currency currency, BigDecimal limitAmount,
                              SpendQuotaPeriod period, boolean enabled, BigDecimal usedAmount) { }
}
