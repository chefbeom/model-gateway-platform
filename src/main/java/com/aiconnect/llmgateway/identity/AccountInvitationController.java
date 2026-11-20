package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AccountInvitationController {
    private final AccountInvitationService service;
    private final AppUserRepository users;

    public AccountInvitationController(AccountInvitationService service, AppUserRepository users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping("/platform/account-invitations")
    public ResponseEntity<AccountInvitationService.IssuedAccountInvitation> issuePlatform(
            HttpServletRequest request,
            @Valid @RequestBody AccountInvitationService.CreateAccountInvitationRequest body) {
        requirePlatformAdmin(request);
        AccountInvitationService.IssuedAccountInvitation issued = service.issue(
                AccountTier.SYSTEM_ADMIN, CurrentActor.userIdOrNull(), null, body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued);
    }

    @GetMapping("/platform/account-invitations")
    public List<AccountInvitationService.AccountInvitationView> listPlatform(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return service.listPlatform();
    }

    @PostMapping("/platform/account-invitations/{invitationId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokePlatform(HttpServletRequest request, @PathVariable UUID invitationId) {
        requirePlatformAdmin(request);
        service.revokePlatform(invitationId, CurrentActor.userIdOrNull());
    }

    @PostMapping("/organizations/{organizationId}/account-invitations")
    public ResponseEntity<AccountInvitationService.IssuedAccountInvitation> issueOrganization(
            HttpServletRequest request,
            @PathVariable UUID organizationId,
            @Valid @RequestBody AccountInvitationService.CreateAccountInvitationRequest body) {
        ActorContext actor = resolveActor(request);
        AccountInvitationService.IssuedAccountInvitation issued = service.issue(
                actor.tier(), actor.userId(), organizationId, body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued);
    }

    @GetMapping("/organizations/{organizationId}/account-invitations")
    public List<AccountInvitationService.AccountInvitationView> listOrganization(
            HttpServletRequest request,
            @PathVariable UUID organizationId) {
        ActorContext actor = resolveActor(request);
        if (actor.tier() == AccountTier.GENERAL_USER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                    "General users cannot view account invitations.");
        }
        return actor.tier() == AccountTier.PROJECT_DIRECTOR
                ? service.listIssuedByDirector(organizationId, actor.userId())
                : service.listOrganization(organizationId);
    }

    @PostMapping("/organizations/{organizationId}/account-invitations/{invitationId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOrganization(HttpServletRequest request,
                                   @PathVariable UUID organizationId,
                                   @PathVariable UUID invitationId) {
        ActorContext actor = resolveActor(request);
        service.revokeOrganization(organizationId, invitationId, actor.tier(), actor.userId());
    }

    private void requirePlatformAdmin(HttpServletRequest request) {
        AuthPrincipal principal = CurrentActor.principal().orElse(null);
        if (!Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin"))
                && (principal == null || !principal.platformAdmin())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED",
                    "Platform administrator permission is required.");
        }
    }

    private ActorContext resolveActor(HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin"))) {
            return new ActorContext(AccountTier.SYSTEM_ADMIN, CurrentActor.userIdOrNull());
        }
        AuthPrincipal principal = CurrentActor.principal()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_AUTH_REQUIRED",
                        "An authenticated administrator is required."));
        if (principal.platformAdmin()) {
            return new ActorContext(AccountTier.SYSTEM_ADMIN, principal.userId());
        }
        AppUser user = users.findById(principal.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_AUTH_REQUIRED",
                        "An authenticated administrator is required."));
        return new ActorContext(AccountTier.effectiveFor(user), user.getId());
    }

    private record ActorContext(AccountTier tier, UUID userId) { }
}
