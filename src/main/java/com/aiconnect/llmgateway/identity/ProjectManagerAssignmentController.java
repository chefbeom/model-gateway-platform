package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.domain.ApiKeyStatus;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit assignment and revocation for project directors; never infer scope from team membership. */
@RestController
@RequestMapping("/api/admin/platform/projects")
public class ProjectManagerAssignmentController {
    private final ProjectRepository projects;
    private final AppUserRepository users;
    private final OrganizationMemberRepository organizations;
    private final ProjectMembershipRepository memberships;
    private final AuditService audit;
    @Autowired(required = false)
    private ApiKeyRepository apiKeys;
    @Autowired(required = false)
    private AccountInvitationService invitations;

    public ProjectManagerAssignmentController(ProjectRepository projects, AppUserRepository users,
            OrganizationMemberRepository organizations, ProjectMembershipRepository memberships, AuditService audit) {
        this.projects = projects;
        this.users = users;
        this.organizations = organizations;
        this.memberships = memberships;
        this.audit = audit;
    }

    @GetMapping("/{projectId}/managers")
    @Transactional(readOnly = true)
    public List<ProjectManagerView> list(@PathVariable UUID projectId, HttpServletRequest request) {
        requirePlatformAdmin(request);
        if (!projects.existsById(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist.");
        }
        return memberships.findByIdProjectIdAndRole(projectId, ProjectMembership.Role.MANAGER).stream()
                .map(membership -> {
                    UUID userId = membership.getId().getUserId();
                    AppUser user = users.findById(userId).orElse(null);
                    long keyCount = apiKeys == null ? 0
                            : apiKeys.countByProjectIdAndIssuedByUserIdAndStatus(projectId, userId, ApiKeyStatus.ACTIVE);
                    return new ProjectManagerView(
                            userId,
                            user == null ? "(deleted user)" : user.getEmail(),
                            user == null ? AccountTier.PROJECT_DIRECTOR.name() : AccountTier.effectiveFor(user).name(),
                            membership.getRole(),
                            membership.isCanIssueApiKeys(),
                            user != null && user.isEnabled(),
                            keyCount,
                            membership.getCreatedAt());
                })
                .sorted(Comparator.comparing(ProjectManagerView::email, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @PutMapping("/{projectId}/managers/{userId}")
    @Transactional
    public void assign(@PathVariable UUID projectId, @PathVariable UUID userId, HttpServletRequest request) {
        requirePlatformAdmin(request);
        var project = projects.findById(projectId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
        var user = users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist."));
        if (!user.isEnabled() || AccountTier.effectiveFor(user) != AccountTier.PROJECT_DIRECTOR ||
                organizations.findByIdOrganizationIdAndIdUserId(project.getOrganizationId(), userId).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_MANAGER_INVALID", "An active project director belonging to this organization is required.");
        }
        var id = new ProjectMembershipId(projectId, userId);
        var current = memberships.findById(id).orElse(null);
        if (current != null && current.getRole() == ProjectMembership.Role.MANAGER) {
            if (!user.isProjectScopeEnforced()) { user.enforceProjectScope(); users.save(user); }
            return;
        }
        if (current != null) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_MEMBERSHIP_CONFLICT", "Existing member role must be reviewed before assigning a manager.");
        }
        user.enforceProjectScope();
        users.save(user);
        memberships.saveAndFlush(new ProjectMembership(projectId, userId, ProjectMembership.Role.MANAGER, true));
        audit.record(project.getOrganizationId(), CurrentActor.userIdOrNull(), "PROJECT_MANAGER_ASSIGNED", "PROJECT", projectId,
                Map.of("userId", userId.toString()));
    }

    @DeleteMapping("/{projectId}/managers/{userId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID projectId, @PathVariable UUID userId, HttpServletRequest request) {
        requirePlatformAdmin(request);
        var project = projects.findById(projectId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
        var user = users.findById(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist."));
        var membership = memberships.findById(new ProjectMembershipId(projectId, userId))
                .filter(item -> item.getRole() == ProjectMembership.Role.MANAGER)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_MANAGER_NOT_FOUND",
                        "The user is not an assigned manager of this project."));
        invitations.revokeProjectMembershipCascade(project, user, membership, true,
                CurrentActor.userIdOrNull(), "PROJECT_MANAGER_REVOKED");
    }

    private void requirePlatformAdmin(HttpServletRequest request) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (!Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin"))
                && (actor == null || !actor.platformAdmin())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED", "Platform administrator permission is required.");
        }
    }

    public record ProjectManagerView(UUID userId, String email, String accountTier, ProjectMembership.Role role,
                                     boolean canIssueApiKeys, boolean enabled, long activeApiKeyCount, Instant createdAt) { }
}
