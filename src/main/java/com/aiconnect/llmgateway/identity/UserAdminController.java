package com.aiconnect.llmgateway.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class UserAdminController {
    private final IdentityService identity;
    private final AuditService audit;
    public UserAdminController(IdentityService identity, AuditService audit) { this.identity = identity; this.audit = audit; }
    @PostMapping("/users")
    public AuthController.UserView createUser(@Valid @RequestBody CreateUser request) {
        AppUser user = identity.createUser(request.email(), request.password(), request.platformAdmin());
        audit.record(null, CurrentActor.userIdOrNull(), "USER_CREATED", "APP_USER", user.getId(), Map.of("platformAdmin", user.isPlatformAdmin()));
        return AuthController.UserView.from(user);
    }
    @PutMapping("/organizations/{organizationId}/members/{userId}")
    public MemberView grantMembership(@PathVariable UUID organizationId, @PathVariable UUID userId, @Valid @RequestBody SetMembership request) {
        OrganizationMember member = identity.grantMembership(organizationId, userId, request.role());
        audit.record(organizationId, CurrentActor.userIdOrNull(), "MEMBERSHIP_GRANTED", "ORGANIZATION_MEMBER", userId, Map.of("role", member.getRole().name()));
        return new MemberView(member.getId().getOrganizationId(), member.getId().getUserId(), member.getRole());
    }
    public record CreateUser(@NotBlank @Email @Size(max = 320) String email, @NotBlank @Size(min = 12, max = 128) String password, boolean platformAdmin) { }
    public record SetMembership(@NotNull OrganizationRole role) { }
    public record MemberView(UUID organizationId, UUID userId, OrganizationRole role) { }
}
