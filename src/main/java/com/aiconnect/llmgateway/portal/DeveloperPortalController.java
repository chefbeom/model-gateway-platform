package com.aiconnect.llmgateway.portal;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.identity.OrganizationMemberRepository;
import com.aiconnect.llmgateway.identity.OrganizationRole;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.ProjectServiceAccessRepository;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The deliberately small, role-safe API surface used by the developer portal.
 * Administrative control-plane endpoints remain under /api/admin.
 */
@RestController
@RequestMapping("/api/portal")
public class DeveloperPortalController {
    private final OrganizationMemberRepository memberships;
    private final ProjectRepository projects;
    private final ProjectServiceAccessRepository serviceAccess;
    private final LlmServiceRepository services;
    private final ApiKeyRepository apiKeys;
    private final ApiKeyService apiKeyService;
    private final TeamAccessService access;

    public DeveloperPortalController(OrganizationMemberRepository memberships, ProjectRepository projects,
                                    ProjectServiceAccessRepository serviceAccess, LlmServiceRepository services,
                                    ApiKeyRepository apiKeys, ApiKeyService apiKeyService, TeamAccessService access) {
        this.memberships = memberships;
        this.projects = projects;
        this.serviceAccess = serviceAccess;
        this.services = services;
        this.apiKeys = apiKeys;
        this.apiKeyService = apiKeyService;
        this.access = access;
    }

    @GetMapping("/session")
    public SessionView session() {
        AuthPrincipal actor = actor();
        List<MembershipView> memberOf = memberships.findByIdUserId(actor.userId()).stream()
                .map(member -> new MembershipView(member.getId().getOrganizationId(), member.getRole()))
                .toList();
        return new SessionView(actor.platformAdmin(), memberOf);
    }

    @GetMapping("/organizations/{organizationId}/projects")
    public List<ProjectView> projectList(@PathVariable UUID organizationId) {
        AuthPrincipal actor = actor();
        requireOrganizationAccess(actor, organizationId);
        return projects.findByOrganizationId(organizationId).stream()
                .filter(project -> access.canViewProject(actor, project.getId()))
                .map(project -> ProjectView.from(project, access.canManageProject(actor, project.getId()), modelsFor(project)))
                .toList();
    }

    @GetMapping("/projects/{projectId}/api-keys")
    public List<ApiKeyView> apiKeys(@PathVariable UUID projectId) {
        AuthPrincipal actor = actor();
        requireProjectView(actor, projectId);
        return apiKeys.findByProjectId(projectId).stream().map(ApiKeyView::from).toList();
    }

    @PostMapping("/projects/{projectId}/api-keys")
    public IssuedApiKey issueApiKey(@PathVariable UUID projectId, @Valid @RequestBody CreateApiKey request) {
        AuthPrincipal actor = actor();
        if (!access.canManageProject(actor, projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_MANAGE_REQUIRED",
                    "Only a project owner, team administrator, or organization administrator can issue API keys.");
        }
        return apiKeyService.issue(projectId, request.name(), request.expiresAt());
    }

    private List<ServiceView> modelsFor(Project project) {
        return serviceAccess.findByIdProjectId(project.getId()).stream()
                .map(item -> services.findById(item.getId().getServiceId()).orElse(null))
                .filter(service -> service != null && service.isEnabled())
                .map(ServiceView::from)
                .toList();
    }

    private AuthPrincipal actor() {
        return CurrentActor.principal().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                "PORTAL_AUTH_REQUIRED", "An authenticated user session is required."));
    }

    private void requireOrganizationAccess(AuthPrincipal actor, UUID organizationId) {
        if (!access.canViewOrganization(actor, organizationId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_SCOPE_REQUIRED",
                    "The current user is not a member of this organization.");
        }
    }

    private void requireProjectView(AuthPrincipal actor, UUID projectId) {
        if (!access.canViewProject(actor, projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED",
                    "The current role cannot view this project.");
        }
    }

    public record SessionView(boolean platformAdmin, List<MembershipView> memberships) { }
    public record MembershipView(UUID organizationId, OrganizationRole role) { }
    public record CreateApiKey(@NotBlank @Size(max = 120) String name, Instant expiresAt) { }
    public record ProjectView(UUID id, String name, String status, boolean canManage, List<ServiceView> services) {
        static ProjectView from(Project project, boolean canManage, List<ServiceView> services) {
            return new ProjectView(project.getId(), project.getName(), project.getStatus(), canManage, services);
        }
    }
    public record ServiceView(String serviceKey, String displayName) {
        static ServiceView from(LlmService service) { return new ServiceView(service.getServiceKey(), service.getDisplayName()); }
    }
    public record ApiKeyView(UUID id, String name, String keyPrefix, String status, Instant expiresAt,
                             Instant lastUsedAt, Instant createdAt) {
        static ApiKeyView from(ApiKey key) {
            return new ApiKeyView(key.getId(), key.getName(), key.getKeyPrefix(), key.getStatus().name(),
                    key.getExpiresAt(), key.getLastUsedAt(), key.getCreatedAt());
        }
    }
}
