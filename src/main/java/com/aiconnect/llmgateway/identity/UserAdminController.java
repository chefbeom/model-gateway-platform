package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.team.TeamRole;
import com.aiconnect.llmgateway.team.TeamService;
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
    private final AuditService audit;

    public UserAdminController(IdentityService identity, TeamService teams, OrganizationAccessService access,
                               AuditService audit) {
        this.identity = identity;
        this.teams = teams;
        this.access = access;
        this.audit = audit;
    }

    @PostMapping("/users")
    public AuthController.UserView createUser(@Valid @RequestBody CreateUser request) {
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
    public OrganizationUserView createOrganizationUser(@PathVariable UUID organizationId,
                                                       @Valid @RequestBody CreateOrganizationUser request) {
        AppUser user = identity.createUser(request.email(), request.password(), false);
        OrganizationMember membership = identity.grantMembership(organizationId, user.getId(), request.organizationRole());
        if (request.teamId() != null) {
            teams.grantMembership(organizationId, request.teamId(), user.getId(),
                    request.teamRole() == null ? TeamRole.DEVELOPER : request.teamRole());
        }
        audit.record(organizationId, CurrentActor.userIdOrNull(), "ORGANIZATION_USER_CREATED", "APP_USER", user.getId(),
                Map.of("organizationRole", membership.getRole().name(), "teamId", String.valueOf(request.teamId())));
        return new OrganizationUserView(user.getId(), user.getEmail(), membership.getRole(), request.teamId(), request.teamRole());
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
    public MemberView grantMembership(@PathVariable UUID organizationId, @PathVariable UUID userId,
                                      @Valid @RequestBody SetMembership request) {
        OrganizationMember member = identity.grantMembership(organizationId, userId, request.role());
        audit.record(organizationId, CurrentActor.userIdOrNull(), "MEMBERSHIP_GRANTED", "ORGANIZATION_MEMBER", userId,
                Map.of("role", member.getRole().name()));
        return new MemberView(member.getId().getOrganizationId(), member.getId().getUserId(), member.getRole());
    }

    public record CreateUser(@NotBlank @Email @Size(max = 320) String email,
                             @NotBlank @Size(min = 12, max = 128) String password, boolean platformAdmin) { }
    public record CreateOrganizationUser(@NotBlank @Email @Size(max = 320) String email,
                                         @NotBlank @Size(min = 12, max = 128) String password,
                                         @NotNull OrganizationRole organizationRole, UUID teamId, TeamRole teamRole) { }
    public record SetMembership(@NotNull OrganizationRole role) { }
    public record MemberView(UUID organizationId, UUID userId, OrganizationRole role) { }
    public record OrganizationUserView(UUID id, String email, OrganizationRole organizationRole, UUID teamId, TeamRole teamRole) { }
}
