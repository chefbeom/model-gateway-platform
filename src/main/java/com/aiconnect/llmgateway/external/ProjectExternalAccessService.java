package com.aiconnect.llmgateway.external;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.identity.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class ProjectExternalAccessService {
    private final ProjectExternalAccessRepository access;
    private final ExternalProviderRepository providers;
    private final ProjectRepository projects;
    private final ModelDeploymentRepository deployments;
    private final LlmRequestRepository requests;
    private final AppUserRepository users;
    private final AuditService audit;

    public ProjectExternalAccessService(ProjectExternalAccessRepository access, ExternalProviderRepository providers,
                                        ProjectRepository projects, ModelDeploymentRepository deployments,
                                        LlmRequestRepository requests, AppUserRepository users, AuditService audit) {
        this.access = access; this.providers = providers; this.projects = projects; this.deployments = deployments;
        this.requests = requests; this.users = users; this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<PortalAccessView> portal(UUID projectId) {
        Project project = requireProject(projectId);
        Map<UUID, ProjectExternalAccess> current = new HashMap<>();
        access.findByProjectIdOrderByCreatedAtDesc(projectId).forEach(item -> current.put(item.getProviderId(), item));
        return providers.findByOrganizationIdAndEnabledTrueOrderByDisplayNameAsc(project.getOrganizationId()).stream()
                .map(provider -> PortalAccessView.from(provider, current.get(provider.getId())))
                .toList();
    }

    @Transactional
    public PortalAccessView request(UUID projectId, UUID providerId, UUID actorId, String reason) {
        Project project = requireProject(projectId);
        ExternalProvider provider = requireProvider(providerId);
        if (!project.getOrganizationId().equals(provider.getOrganizationId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_PROVIDER_ORGANIZATION_MISMATCH",
                    "The provider belongs to another organization.");
        }
        ProjectExternalAccess item = access.findByProjectIdAndProviderId(projectId, providerId)
                .map(existing -> { existing.request(actorId, reason); return existing; })
                .orElseGet(() -> new ProjectExternalAccess(projectId, providerId, actorId, reason));
        item = access.save(item);
        audit.record(project.getOrganizationId(), actorId, "EXTERNAL_ACCESS_REQUESTED", "PROJECT_EXTERNAL_ACCESS",
                item.getId(), Map.of("projectId", projectId, "providerId", providerId, "reason", item.getRequestedReason()));
        return PortalAccessView.from(provider, item);
    }

    @Transactional(readOnly = true)
    public List<AdminAccessView> organization(UUID organizationId) {
        List<Project> scoped = projects.findByOrganizationId(organizationId);
        if (scoped.isEmpty()) return List.of();
        Map<UUID, Project> projectMap = new HashMap<>();
        scoped.forEach(project -> projectMap.put(project.getId(), project));
        Map<UUID, ExternalProvider> providerMap = new HashMap<>();
        providers.findByOrganizationIdOrderByDisplayNameAsc(organizationId).forEach(provider -> providerMap.put(provider.getId(), provider));
        return access.findByProjectIdInOrderByCreatedAtDesc(new ArrayList<>(projectMap.keySet())).stream()
                .map(item -> AdminAccessView.from(item, projectMap.get(item.getProjectId()), providerMap.get(item.getProviderId()), email(item.getRequestedByUserId())))
                .filter(Objects::nonNull).toList();
    }

    @Transactional
    public AdminAccessView decide(UUID projectId, UUID providerId, ExternalAccessStatus status,
                                  boolean manualAllowed, boolean autoFailoverEnabled,
                                  BigDecimal monthlyCostLimit, Instant expiresAt, UUID actorId) {
        Project project = requireProject(projectId);
        ExternalProvider provider = requireProvider(providerId);
        if (!project.getOrganizationId().equals(provider.getOrganizationId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_PROVIDER_ORGANIZATION_MISMATCH",
                    "The provider belongs to another organization.");
        }
        ProjectExternalAccess item = access.findByProjectIdAndProviderId(projectId, providerId)
                .orElseGet(() -> new ProjectExternalAccess(projectId, providerId, actorId,
                        "Granted directly by an administrator."));
        item.decide(status, manualAllowed, autoFailoverEnabled, monthlyCostLimit, expiresAt, actorId);
        access.save(item);
        audit.record(project.getOrganizationId(), actorId, "EXTERNAL_ACCESS_DECIDED", "PROJECT_EXTERNAL_ACCESS",
                item.getId(), Map.of("projectId", projectId, "providerId", providerId, "status", status.name(),
                        "manualAllowed", item.isManualAllowed(), "autoFailoverEnabled", item.isAutoFailoverEnabled()));
        return AdminAccessView.from(item, project, provider, email(item.getRequestedByUserId()));
    }

    @Transactional(readOnly = true)
    public boolean allowsManual(UUID projectId, UUID providerId) {
        return allowed(projectId, providerId, false);
    }

    @Transactional(readOnly = true)
    public boolean allowsAutoFailover(UUID projectId, UUID providerId) {
        return allowed(projectId, providerId, true);
    }

    private boolean allowed(UUID projectId, UUID providerId, boolean automatic) {
        ProjectExternalAccess item = access.findByProjectIdAndProviderId(projectId, providerId).orElse(null);
        if (item == null || !item.isActive()) return false;
        if (automatic ? !item.isAutoFailoverEnabled() : !item.isManualAllowed()) return false;
        if (item.getMonthlyCostLimit() == null) return true;
        List<UUID> deploymentIds = deployments.findByExternalProviderId(providerId).stream().map(ModelDeployment::getId).toList();
        if (deploymentIds.isEmpty()) return false;
        Instant monthStart = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal spent = requests.sumCostByProjectAndDeploymentsSince(projectId, deploymentIds, monthStart);
        return spent == null || spent.compareTo(item.getMonthlyCostLimit()) < 0;
    }

    private String email(UUID userId) { return userId == null ? null : users.findById(userId).map(AppUser::getEmail).orElse("삭제된 사용자"); }
    private Project requireProject(UUID id) { return projects.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist.")); }
    private ExternalProvider requireProvider(UUID id) { return providers.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXTERNAL_PROVIDER_NOT_FOUND", "The external provider does not exist.")); }

    public record PortalAccessView(UUID providerId, String providerName, String providerType, String status,
                                   boolean manualAllowed, boolean autoFailoverEnabled, BigDecimal monthlyCostLimit,
                                   Instant expiresAt, String requestedReason) {
        static PortalAccessView from(ExternalProvider provider, ProjectExternalAccess item) {
            return new PortalAccessView(provider.getId(), provider.getDisplayName(), provider.getProviderType().name(),
                    item == null ? "NOT_REQUESTED" : item.getStatus().name(), item != null && item.isManualAllowed(),
                    item != null && item.isAutoFailoverEnabled(), item == null ? null : item.getMonthlyCostLimit(),
                    item == null ? null : item.getExpiresAt(), item == null ? null : item.getRequestedReason());
        }
    }
    public record AdminAccessView(UUID id, UUID projectId, String projectName, UUID providerId, String providerName,
                                  String status, String requestedBy, String requestedReason, boolean manualAllowed,
                                  boolean autoFailoverEnabled, BigDecimal monthlyCostLimit, Instant expiresAt,
                                  Instant createdAt) {
        static AdminAccessView from(ProjectExternalAccess item, Project project, ExternalProvider provider, String requester) {
            if (project == null || provider == null) return null;
            return new AdminAccessView(item.getId(), project.getId(), project.getName(), provider.getId(), provider.getDisplayName(),
                    item.getStatus().name(), requester, item.getRequestedReason(), item.isManualAllowed(),
                    item.isAutoFailoverEnabled(), item.getMonthlyCostLimit(), item.getExpiresAt(), item.getCreatedAt());
        }
    }
}
