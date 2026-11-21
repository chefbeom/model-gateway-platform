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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
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
        AccountTier tier = actor.accountTier() != null
                ? actor.accountTier()
                : (actor.platformAdmin() ? AccountTier.SYSTEM_ADMIN : AccountTier.GENERAL_USER);
        String mfaState = !actor.totpEnabled()
                ? "ENROLLMENT_REQUIRED"
                : (actor.mfaVerified() ? "VERIFIED" : "VERIFICATION_REQUIRED");
        return new SessionView(actor.platformAdmin(), tier.name(), actor.totpEnabled(), mfaState, memberOf);
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
                .map(project -> ProjectView.from(project,
                        "ACTIVE".equalsIgnoreCase(project.getStatus()) && access.canIssueProjectApiKeys(actor, project.getId()),
                        modelsFor(project)))
                .toList();
    }

    @GetMapping("/projects/{projectId}/api-keys")
    public List<ApiKeyView> apiKeys(@PathVariable UUID projectId) {
        AuthPrincipal actor = actor();
        requireProjectView(actor, projectId);
        DataBoundaryPolicy projectBoundary = projects.findById(projectId)
                .map(Project::effectiveDataBoundaryPolicy)
                .orElse(DataBoundaryPolicy.EXTERNAL_ALLOWED);
        return apiKeys.findByProjectId(projectId).stream()
                .filter(key -> access.canManageProject(actor, projectId) || actor.userId().equals(key.getIssuedByUserId()))
                .map(key -> ApiKeyView.from(key, canManageKey(actor, key), projectBoundary))
                .toList();
    }

    @PostMapping("/projects/{projectId}/api-keys")
    public IssuedApiKey issueApiKey(@PathVariable UUID projectId, @Valid @RequestBody CreateApiKey request) {
        AuthPrincipal actor = actor();
        requireProjectView(actor, projectId);
        requireApiKeyIssuancePermission(actor, projectId);
        return apiKeyService.issue(projectId, request.name(), request.expiresAt(), actor.userId(), request.dataBoundaryPolicy());
    }

    @PostMapping("/projects/{projectId}/api-keys/temporary")
    public IssuedApiKey issueTemporaryApiKey(@PathVariable UUID projectId, @Valid @RequestBody CreateTemporaryApiKey request) {
        AuthPrincipal actor = actor();
        requireProjectView(actor, projectId);
        requireApiKeyIssuancePermission(actor, projectId);
        return apiKeyService.issueTemporary(projectId, request.name(), request.durationSeconds(), actor.userId(), request.dataBoundaryPolicy());
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

    private void requireApiKeyIssuancePermission(AuthPrincipal actor, UUID projectId) {
        if (!access.canIssueProjectApiKeys(actor, projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "API_KEY_ISSUANCE_NOT_DELEGATED",
                    "API key issuance permission has not been delegated to your account for this project.");
        }
    }

    public record SessionView(boolean platformAdmin, String accountTier, boolean totpEnabled, String mfaState,
                              List<MembershipView> memberships) { }
    public record ConnectionView(List<ConnectionEndpointView> endpoints) { }
    public record ConnectionEndpointView(String scope, String label, String url) { }
    public record MembershipView(UUID organizationId, OrganizationRole role) { }
    public record CreateApiKey(@NotBlank @Size(max = 120) String name, Instant expiresAt,
                               DataBoundaryPolicy dataBoundaryPolicy) {
        public CreateApiKey(String name, Instant expiresAt) {
            this(name, expiresAt, null);
        }
    }
    public record CreateTemporaryApiKey(@NotBlank @Size(max = 120) String name,
                                        @Min(60) @Max(86400) long durationSeconds,
                                        DataBoundaryPolicy dataBoundaryPolicy) {
        public CreateTemporaryApiKey(String name, long durationSeconds) {
            this(name, durationSeconds, null);
        }
    }
    public record ProjectView(UUID id, String name, String status, boolean canIssueApiKeys,
                              DataBoundaryPolicy dataBoundaryPolicy, DataBoundaryPolicy maxDataBoundaryPolicy,
                              DataBoundaryPolicy effectiveDataBoundaryPolicy, List<ServiceView> services) {
        static ProjectView from(Project project, boolean canIssueApiKeys, List<ServiceView> services) {
            return new ProjectView(project.getId(), project.getName(), project.getStatus(), canIssueApiKeys,
                    project.getDataBoundaryPolicy(), project.getMaxDataBoundaryPolicy(),
                    project.effectiveDataBoundaryPolicy(), services);
        }
    }
    public record ServiceView(String serviceKey, String displayName) {
        static ServiceView from(LlmService service) { return new ServiceView(service.getServiceKey(), service.getDisplayName()); }
    }
    public record ApiKeyView(UUID id, String name, String keyPrefix, String status, Instant expiresAt,
                             Instant lastUsedAt, Instant createdAt, boolean canRevoke, boolean canDelete,
                             DataBoundaryPolicy dataBoundaryPolicy, DataBoundaryPolicy effectiveDataBoundaryPolicy) {
        static ApiKeyView from(ApiKey key, boolean canManage, DataBoundaryPolicy projectBoundary) {
            return new ApiKeyView(key.getId(), key.getName(), key.getKeyPrefix(), key.getStatus().name(),
                    key.getExpiresAt(), key.getLastUsedAt(), key.getCreatedAt(), canManage,
                    canManage && key.getStatus() == ApiKeyStatus.REVOKED,
                    key.getDataBoundaryPolicy(), DataBoundaryPolicy.strictest(projectBoundary, key.getDataBoundaryPolicy()));
        }
    }
}
