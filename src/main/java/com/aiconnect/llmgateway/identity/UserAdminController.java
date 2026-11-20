package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.team.TeamRole;
import com.aiconnect.llmgateway.team.TeamService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        boolean platformAdmin = isPlatformAdmin(httpRequest);
        if (request.organizationRole() == OrganizationRole.ORGANIZATION_ADMIN && !platformAdmin) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                    "Only a system administrator can create a project director.");
        }
        AccountTier targetTier = request.organizationRole() == OrganizationRole.ORGANIZATION_ADMIN
                ? AccountTier.PROJECT_DIRECTOR
                : AccountTier.GENERAL_USER;
        AppUser user = identity.createUser(request.email(), request.password(), false, targetTier, CurrentActor.userIdOrNull());
        OrganizationMember membership = identity.grantMembership(organizationId, user.getId(), request.organizationRole());
        if (request.teamId() != null) {
            teams.grantMembership(organizationId, request.teamId(), user.getId(),
                    request.teamRole() == null ? TeamRole.DEVELOPER : request.teamRole());
        }
        audit.record(organizationId, CurrentActor.userIdOrNull(), "ORGANIZATION_USER_CREATED", "APP_USER", user.getId(),
                Map.of("organizationRole", membership.getRole().name(), "teamId", String.valueOf(request.teamId())));
        return new OrganizationUserView(user.getId(), user.getEmail(), membership.getRole(),
                AccountTier.effectiveFor(user).name(), user.isTotpEnabled(), request.teamId(), request.teamRole());
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
        if (request.role() == OrganizationRole.ORGANIZATION_ADMIN && !isPlatformAdmin(httpRequest)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                    "Only a system administrator can assign organization administrator roles.");
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
