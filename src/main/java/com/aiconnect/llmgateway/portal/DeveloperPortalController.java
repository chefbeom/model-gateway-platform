package com.aiconnect.llmgateway.portal;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.identity.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.*;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Role-safe API surface used by the developer portal. */
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
    private final String internalBaseUrl;
    private final String externalBaseUrl;

    public DeveloperPortalController(OrganizationMemberRepository memberships, ProjectRepository projects,
                                    ProjectServiceAccessRepository serviceAccess, LlmServiceRepository services,
                                    ApiKeyRepository apiKeys, ApiKeyService apiKeyService, TeamAccessService access,
                                    @Value("${gateway.internal-base-url:}") String internalBaseUrl,
                                    @Value("${gateway.external-base-url:}") String externalBaseUrl) {
        this.memberships = memberships;
        this.projects = projects;
        this.serviceAccess = serviceAccess;
        this.services = services;
        this.apiKeys = apiKeys;
        this.apiKeyService = apiKeyService;
        this.access = access;
        this.internalBaseUrl = internalBaseUrl;
        this.externalBaseUrl = externalBaseUrl;
    }

    @GetMapping("/session")
    public SessionView session() {
        AuthPrincipal actor = actor();
        List<MembershipView> memberOf = memberships.findByIdUserId(actor.userId()).stream()
                .map(member -> new MembershipView(member.getId().getOrganizationId(), member.getRole()))
                .toList();
        return new SessionView(actor.platformAdmin(), memberOf);
    }

    @GetMapping("/connection")
    public ConnectionView connection() {
        actor();
        return new ConnectionView(List.of(
                endpoint("INTERNAL", "Internal network / VPN", internalBaseUrl),
                endpoint("EXTERNAL", "External HTTPS", externalBaseUrl)
        ).stream().filter(item -> item.url() != null).toList());
    }

    @GetMapping("/organizations/{organizationId}/projects")
    public List<ProjectView> projectList(@PathVariable UUID organizationId) {
        AuthPrincipal actor = actor();
        requireOrganizationAccess(actor, organizationId);
        return projects.findByOrganizationId(organizationId).stream()
                .filter(project -> access.canViewProject(actor, project.getId()))
                .map(project -> ProjectView.from(project, "ACTIVE".equalsIgnoreCase(project.getStatus()), modelsFor(project)))
                .toList();
    }

    @GetMapping("/projects/{projectId}/api-keys")
    public List<ApiKeyView> apiKeys(@PathVariable UUID projectId) {
        AuthPrincipal actor = actor();
        requireProjectView(actor, projectId);
        return apiKeys.findByProjectId(projectId).stream().map(key -> ApiKeyView.from(key, canManageKey(actor, key))).toList();
    }

    @PostMapping("/projects/{projectId}/api-keys")
    public IssuedApiKey issueApiKey(@PathVariable UUID projectId, @Valid @RequestBody CreateApiKey request) {
        AuthPrincipal actor = actor();
        requireProjectView(actor, projectId);
        return apiKeyService.issue(projectId, request.name(), request.expiresAt(), actor.userId());
    }

    @DeleteMapping("/projects/{projectId}/api-keys/{apiKeyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(@PathVariable UUID projectId, @PathVariable UUID apiKeyId) {
        ApiKey key = managedKey(actor(), projectId, apiKeyId);
        if (key.getStatus() == ApiKeyStatus.ACTIVE) apiKeyService.revoke(apiKeyId);
    }

    @DeleteMapping("/projects/{projectId}/api-keys/{apiKeyId}/record")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApiKeyRecord(@PathVariable UUID projectId, @PathVariable UUID apiKeyId) {
        managedKey(actor(), projectId, apiKeyId);
        apiKeyService.deleteRevoked(apiKeyId);
    }

    private ApiKey managedKey(AuthPrincipal actor, UUID projectId, UUID apiKeyId) {
        requireProjectView(actor, projectId);
        ApiKey key = apiKeys.findById(apiKeyId).filter(item -> item.getProjectId().equals(projectId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "The API key does not exist in this project."));
        if (!canManageKey(actor, key)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "API_KEY_MANAGEMENT_DENIED",
                    "Only the key issuer, project owner, team administrator, or organization administrator can manage this API key.");
        }
        return key;
    }

    private boolean canManageKey(AuthPrincipal actor, ApiKey key) {
        return actor.userId().equals(key.getIssuedByUserId()) || access.canManageProject(actor, key.getProjectId());
    }

    private ConnectionEndpointView endpoint(String scope, String label, String value) {
        if (value == null || value.isBlank()) return new ConnectionEndpointView(scope, label, null);
        String normalized = value.trim().replaceAll("/+$", "");
        if (!normalized.endsWith("/v1")) normalized += "/v1";
        return new ConnectionEndpointView(scope, label, normalized);
    }

    private List<ServiceView> modelsFor(Project project) {
        return serviceAccess.findByIdProjectId(project.getId()).stream()
                .map(item -> services.findById(item.getId().getServiceId()).orElse(null))
                .filter(service -> service != null && service.isEnabled())
                .map(ServiceView::from).toList();
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
                    "The current role cannot access this project.");
        }
    }

    public record SessionView(boolean platformAdmin, List<MembershipView> memberships) { }
    public record ConnectionView(List<ConnectionEndpointView> endpoints) { }
    public record ConnectionEndpointView(String scope, String label, String url) { }
    public record MembershipView(UUID organizationId, OrganizationRole role) { }
    public record CreateApiKey(@NotBlank @Size(max = 120) String name, Instant expiresAt) { }
    public record ProjectView(UUID id, String name, String status, boolean canIssueApiKeys, List<ServiceView> services) {
        static ProjectView from(Project project, boolean canIssueApiKeys, List<ServiceView> services) {
            return new ProjectView(project.getId(), project.getName(), project.getStatus(), canIssueApiKeys, services);
        }
    }
    public record ServiceView(String serviceKey, String displayName) {
        static ServiceView from(LlmService service) { return new ServiceView(service.getServiceKey(), service.getDisplayName()); }
    }
    public record ApiKeyView(UUID id, String name, String keyPrefix, String status, Instant expiresAt,
                             Instant lastUsedAt, Instant createdAt, boolean canRevoke, boolean canDelete) {
        static ApiKeyView from(ApiKey key, boolean canManage) {
            return new ApiKeyView(key.getId(), key.getName(), key.getKeyPrefix(), key.getStatus().name(),
                    key.getExpiresAt(), key.getLastUsedAt(), key.getCreatedAt(), canManage,
                    canManage && key.getStatus() == ApiKeyStatus.REVOKED);
        }
    }
}
