package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.TeamRole;
import com.aiconnect.llmgateway.team.TeamService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class UserAdminController {
    private final IdentityService identity;
    private final TeamService teams;
    private final OrganizationAccessService access;
    private final TotpService totpService;
    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository organizationMembers;
    private final AuditService audit;
    @Autowired(required = false)
    private ProjectRepository projects;
    @Autowired(required = false)
    private ProjectMembershipRepository projectMemberships;

    public UserAdminController(IdentityService identity, TeamService teams, OrganizationAccessService access,
                               TotpService totpService, AppUserRepository users, OrganizationRepository organizations,
                               OrganizationMemberRepository organizationMembers, AuditService audit) {
        this.identity = identity;
        this.teams = teams;
        this.access = access;
        this.totpService = totpService;
        this.users = users;
        this.organizations = organizations;
        this.organizationMembers = organizationMembers;
        this.audit = audit;
    }

    @PostMapping("/users")
    public AuthController.UserView createUser(HttpServletRequest httpRequest, @Valid @RequestBody CreateUser request) {
        if (!isPlatformAdmin(httpRequest)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator permission is required.");
        }
        AppUser user = identity.createUser(request.email(), request.password(), request.platformAdmin());
        audit.record(null, CurrentActor.userIdOrNull(), "USER_CREATED", "APP_USER", user.getId(),
                Map.of("platformAdmin", user.isPlatformAdmin()));
        return AuthController.UserView.from(user);
    }

    @GetMapping("/organizations/{organizationId}/users")
    public List<OrganizationAccessService.OrganizationUserView> listOrganizationUsers(@PathVariable UUID organizationId) {
        return access.organizationUsers(organizationId);
    }

    @PostMapping("/organizations/{organizationId}/users")
    public OrganizationUserView createOrganizationUser(HttpServletRequest httpRequest,
                                                       @PathVariable UUID organizationId,
                                                       @Valid @RequestBody CreateOrganizationUser request) {
        throw new ApiException(HttpStatus.GONE, "DIRECT_USER_CREATION_DISABLED",
                "Direct user creation is disabled; issue an account invitation instead.");
    }

    @PostMapping("/organizations/{organizationId}/users/{userId}/totp-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetOrganizationUserTotp(HttpServletRequest httpRequest,
                                          @PathVariable UUID organizationId,
                                          @PathVariable UUID userId) {
        if (!organizations.existsById(organizationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist.");
        }
        OrganizationMember targetMembership = organizationMembers
                .findByIdOrganizationIdAndIdUserId(organizationId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_MEMBER_NOT_FOUND",
                        "The user is not a member of this organization."));
        AppUser targetUser = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist."));

        if (!isPlatformAdmin(httpRequest)) {
            AuthPrincipal actor = CurrentActor.principal()
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_AUTH_REQUIRED",
                            "An authenticated administrator is required."));
            AppUser actorUser = users.findById(actor.userId())
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_AUTH_REQUIRED",
                            "An authenticated administrator is required."));
            if (AccountTier.effectiveFor(actorUser) != AccountTier.PROJECT_DIRECTOR) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                        "Only a system administrator or project director can reset TOTP.");
            }
            if (AccountTier.effectiveFor(targetUser) != AccountTier.GENERAL_USER
                    || targetMembership.getRole() == OrganizationRole.ORGANIZATION_ADMIN) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                        "Project directors can only reset TOTP for general users in their organization.");
            }
            if (projects != null && projectMemberships != null) {
                boolean sharedManagedProject = projects.findByOrganizationId(organizationId).stream()
                        .map(Project::getId)
                        .anyMatch(projectId ->
                                projectMemberships.existsByIdProjectIdAndIdUserIdAndRole(
                                        projectId, actor.userId(), ProjectMembership.Role.MANAGER)
                                        && projectMemberships.findById(new ProjectMembershipId(projectId, userId)).isPresent());
                if (!sharedManagedProject) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED",
                            "Project directors can only reset TOTP for members of projects they manage.");
                }
            }
        }
        totpService.resetTotp(userId, CurrentActor.userIdOrNull(), organizationId);
    }

    @GetMapping("/organizations/{organizationId}/users/{userId}/removal-preview")
    public OrganizationAccessService.UserRemovalPreview removalPreview(@PathVariable UUID organizationId,
                                                                        @PathVariable UUID userId) {
        return access.userRemovalPreview(organizationId, userId);
    }

    @DeleteMapping("/organizations/{organizationId}/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeOrganizationUser(@PathVariable UUID organizationId, @PathVariable UUID userId) {
        access.removeOrganizationUser(organizationId, userId);
    }

    @PutMapping("/organizations/{organizationId}/members/{userId}")
    public MemberView grantMembership(HttpServletRequest httpRequest, @PathVariable UUID organizationId,
                                      @PathVariable UUID userId, @Valid @RequestBody SetMembership request) {
        if (!isPlatformAdmin(httpRequest)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator permission is required.");
        }
        OrganizationMember member = identity.grantMembership(organizationId, userId, request.role());
        audit.record(organizationId, CurrentActor.userIdOrNull(), "MEMBERSHIP_GRANTED", "ORGANIZATION_MEMBER", userId,
                Map.of("role", member.getRole().name()));
        return new MemberView(member.getId().getOrganizationId(), member.getId().getUserId(), member.getRole());
    }

    private boolean isPlatformAdmin(HttpServletRequest request) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        return Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin"))
                || (actor != null && actor.platformAdmin());
    }

    public record CreateUser(@NotBlank @Email @Size(max = 320) String email,
                             @NotBlank @Size(min = 12, max = 128) String password, boolean platformAdmin) { }
    public record CreateOrganizationUser(@NotBlank @Email @Size(max = 320) String email,
                                         @NotBlank @Size(min = 12, max = 128) String password,
                                         @NotNull OrganizationRole organizationRole, UUID teamId, TeamRole teamRole) { }
    public record SetMembership(@NotNull OrganizationRole role) { }
    public record MemberView(UUID organizationId, UUID userId, OrganizationRole role) { }
    public record OrganizationUserView(UUID id, String email, OrganizationRole organizationRole, String accountTier,
                                       boolean totpEnabled, UUID teamId, TeamRole teamRole) { }
}
