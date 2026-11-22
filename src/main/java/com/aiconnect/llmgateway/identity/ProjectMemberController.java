package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.domain.ApiKeyStatus;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Project-scoped member visibility, API-key issuance delegation, and cascade revocation. */
@RestController
@RequestMapping("/api/admin/projects/{projectId}/members")
public class ProjectMemberController {
    private final ProjectRepository projects;
    private final ProjectMembershipRepository memberships;
    private final AppUserRepository users;
    private final ApiKeyRepository apiKeys;
    private final AccountInvitationService invitations;
    private final TeamAccessService access;
    private final AuditService audit;

    public ProjectMemberController(ProjectRepository projects,
                                   ProjectMembershipRepository memberships,
                                   AppUserRepository users,
                                   ApiKeyRepository apiKeys,
                                   AccountInvitationService invitations,
                                   TeamAccessService access,
                                   AuditService audit) {
        this.projects = projects;
        this.memberships = memberships;
        this.users = users;
        this.apiKeys = apiKeys;
        this.invitations = invitations;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ProjectMemberView> list(@PathVariable UUID projectId, HttpServletRequest http) {
        Project project = requireProject(projectId);
        if (!isOrganizationOrPlatformAdmin(http, project.getOrganizationId())) {
            AuthPrincipal actor = CurrentActor.principal().orElse(null);
            if (actor == null || !access.canManageProject(actor, projectId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED",
                        "Only a project manager or administrator may list project members.");
            }
        }
        return memberships.findByIdProjectId(projectId).stream()
                .map(membership -> toView(projectId, membership))
                .sorted(Comparator.comparing((ProjectMemberView v) -> v.role() == ProjectMembership.Role.MANAGER ? 0 : 1)
                        .thenComparing(ProjectMemberView::email, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @PatchMapping("/{userId}")
    @Transactional
    public ProjectMemberView updateDelegation(@PathVariable UUID projectId, @PathVariable UUID userId,
                                              @Valid @RequestBody UpdateDelegationRequest request) {
        Project project = requireProject(projectId);
        ProjectMembership membership = memberships.findById(new ProjectMembershipId(projectId, userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_MEMBER_NOT_FOUND",
                        "The user is not a member of this project."));
        if (membership.getRole() == ProjectMembership.Role.MANAGER) {
            throw new ApiException(HttpStatus.CONFLICT, "MANAGER_DELEGATION_IMMUTABLE",
                    "Project managers always retain API key issuance permissions.");
        }
        membership.setCanIssueApiKeys(request.canIssueApiKeys());
        ProjectMembership saved = memberships.saveAndFlush(membership);
        audit.record(project.getOrganizationId(), CurrentActor.userIdOrNull(), "PROJECT_MEMBER_DELEGATION_UPDATED",
                "PROJECT", projectId,
                Map.of("userId", userId.toString(), "canIssueApiKeys", saved.isCanIssueApiKeys()));
        return toView(projectId, saved);
    }

    @DeleteMapping("/{userId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID projectId, @PathVariable UUID userId, HttpServletRequest http) {
        Project project = requireProject(projectId);
        ProjectMembership membership = memberships.findById(new ProjectMembershipId(projectId, userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_MEMBER_NOT_FOUND",
                        "The user is not a member of this project."));
        boolean isManager = membership.getRole() == ProjectMembership.Role.MANAGER;
        if (isManager && !isOrganizationOrPlatformAdmin(http, project.getOrganizationId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MANAGER_REVOCATION_REQUIRES_ADMIN",
                    "Only a platform or organization administrator may revoke a project manager.");
        }
        AppUser user = users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist."));
        invitations.revokeProjectMembershipCascade(project, user, membership, isManager,
                CurrentActor.userIdOrNull(), isManager ? "PROJECT_MANAGER_REVOKED" : "PROJECT_MEMBER_REVOKED");
    }

    private Project requireProject(UUID projectId) {
        return projects.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
    }

    private boolean isOrganizationOrPlatformAdmin(HttpServletRequest http, UUID organizationId) {
        if (http != null && Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin"))) {
            return true;
        }
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        return actor != null && (actor.platformAdmin() || access.isOrganizationAdmin(actor, organizationId));
    }

    private ProjectMemberView toView(UUID projectId, ProjectMembership membership) {
        UUID userId = membership.getId().getUserId();
        AppUser user = users.findById(userId).orElse(null);
        long activeKeys = apiKeys.countByProjectIdAndIssuedByUserIdAndStatus(projectId, userId, ApiKeyStatus.ACTIVE);
        return new ProjectMemberView(
                userId,
                user == null ? "(deleted user)" : user.getEmail(),
                user == null ? AccountTier.GENERAL_USER.name() : AccountTier.effectiveFor(user).name(),
                membership.getRole(),
                membership.isCanIssueApiKeys(),
                activeKeys,
                membership.getCreatedAt());
    }

    public record UpdateDelegationRequest(@NotNull Boolean canIssueApiKeys) { }

    public record ProjectMemberView(UUID userId, String email, String accountTier, ProjectMembership.Role role,
                                    boolean canIssueApiKeys, long activeApiKeyCount, Instant createdAt) { }
}
