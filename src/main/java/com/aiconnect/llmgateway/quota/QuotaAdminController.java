package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class QuotaAdminController {
    private final ProjectRepository projects;
    private final ProjectQuotaRepository quotas;
    private final TeamAccessService access;

    @Autowired
    public QuotaAdminController(ProjectRepository projects, ProjectQuotaRepository quotas, TeamAccessService access) {
        this.projects = projects;
        this.quotas = quotas;
        this.access = access;
    }

    public QuotaAdminController(ProjectRepository projects, ProjectQuotaRepository quotas) {
        this(projects, quotas, null);
    }

    @PutMapping("/api/admin/projects/{projectId}/quota")
    @Transactional
    public QuotaView setQuota(@PathVariable UUID projectId, @Valid @RequestBody SetQuota request, HttpServletRequest http) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
        ProjectQuota quota = quotas.findById(projectId).orElseGet(() -> new ProjectQuota(projectId, 60, null));
        if (!isPlatformOrOrgAdmin(http, project.getOrganizationId())) {
            if (quota.getMaxRequestsPerMinute() != null && request.requestsPerMinute() > quota.getMaxRequestsPerMinute()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "QUOTA_CEILING_EXCEEDED",
                        "Requested requestsPerMinute (" + request.requestsPerMinute()
                                + ") exceeds the administrator ceiling (" + quota.getMaxRequestsPerMinute() + ").");
            }
            if (quota.getMaxMonthlyTokenLimit() != null
                    && (request.monthlyTokenLimit() == null || request.monthlyTokenLimit() > quota.getMaxMonthlyTokenLimit())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "QUOTA_CEILING_EXCEEDED",
                        "Requested monthlyTokenLimit exceeds or attempts to clear the administrator ceiling ("
                                + quota.getMaxMonthlyTokenLimit() + ").");
            }
        }
        quota.configureQuota(request.requestsPerMinute(), request.monthlyTokenLimit());
        quota = quotas.save(quota);
        return QuotaView.from(quota);
    }

    @GetMapping("/api/admin/projects/{projectId}/quota")
    @Transactional(readOnly = true)
    public QuotaView getQuota(@PathVariable UUID projectId) {
        ProjectQuota quota = quotas.findById(projectId).orElse(new ProjectQuota(projectId, 60, null));
        return QuotaView.from(quota);
    }

    @GetMapping("/api/admin/platform/projects/{projectId}/quota-ceiling")
    @Transactional(readOnly = true)
    public QuotaView getQuotaCeiling(@PathVariable UUID projectId, HttpServletRequest http) {
        requirePlatformAdmin(http);
        if (!projects.existsById(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist.");
        }
        ProjectQuota quota = quotas.findById(projectId).orElse(new ProjectQuota(projectId, 60, null));
        return QuotaView.from(quota);
    }

    @PutMapping("/api/admin/platform/projects/{projectId}/quota-ceiling")
    @Transactional
    public QuotaView setQuotaCeiling(@PathVariable UUID projectId,
                                     @Valid @RequestBody SetQuotaCeiling request,
                                     HttpServletRequest http) {
        requirePlatformAdmin(http);
        if (!projects.existsById(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist.");
        }
        ProjectQuota quota = quotas.findById(projectId).orElseGet(() -> new ProjectQuota(projectId, 60, null));
        quota.configureCeiling(request.maxRequestsPerMinute(), request.maxMonthlyTokenLimit());
        quota = quotas.save(quota);
        return QuotaView.from(quota);
    }

    private void requirePlatformAdmin(HttpServletRequest http) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (!Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin")) && (actor == null || !actor.platformAdmin())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED", "Platform administrator permission is required.");
        }
    }

    private boolean isPlatformOrOrgAdmin(HttpServletRequest http, UUID organizationId) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin")) || (actor != null && actor.platformAdmin())) {
            return true;
        }
        return access != null && access.isOrganizationAdmin(actor, organizationId);
    }

    public record SetQuota(@Min(1) int requestsPerMinute, @Min(1) Long monthlyTokenLimit) { }
    public record SetQuotaCeiling(@Min(1) Integer maxRequestsPerMinute, @Min(1) Long maxMonthlyTokenLimit) { }
    public record QuotaView(UUID projectId, int requestsPerMinute, Long monthlyTokenLimit,
                            Integer maxRequestsPerMinute, Long maxMonthlyTokenLimit) {
        static QuotaView from(ProjectQuota quota) {
            return new QuotaView(quota.getProjectId(), quota.getRequestsPerMinute(), quota.getMonthlyTokenLimit(),
                    quota.getMaxRequestsPerMinute(), quota.getMaxMonthlyTokenLimit());
        }
    }
}
