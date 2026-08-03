package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Platform administrator only: global inventory, cleanup previews and explicit destructive actions. */
@RestController
@RequestMapping("/api/admin/platform")
public class PlatformAdministrationController {
    private final PlatformAdministrationService service;

    public PlatformAdministrationController(PlatformAdministrationService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public PlatformAdministrationService.PlatformOverview overview(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return service.overview();
    }

    @GetMapping("/organizations")
    public List<PlatformAdministrationService.OrganizationSummary> organizations(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return service.organizations();
    }

    @GetMapping("/organizations/{organizationId}/cleanup-preview")
    public PlatformAdministrationService.OrganizationCleanupPreview cleanupPreview(HttpServletRequest request,
                                                                                     @PathVariable UUID organizationId) {
        requirePlatformAdmin(request);
        return service.cleanupPreview(organizationId);
    }

    @PostMapping("/organizations/{organizationId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspendOrganization(HttpServletRequest request, @PathVariable UUID organizationId) {
        requirePlatformAdmin(request);
        service.suspendOrganization(organizationId);
    }

    @PostMapping("/organizations/{organizationId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreOrganization(HttpServletRequest request, @PathVariable UUID organizationId) {
        requirePlatformAdmin(request);
        service.restoreOrganization(organizationId);
    }

    @DeleteMapping("/organizations/{organizationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOrganization(HttpServletRequest request, @PathVariable UUID organizationId,
                                   @RequestParam String confirmation, @RequestParam(defaultValue = "false") boolean purgeHistory) {
        requirePlatformAdmin(request);
        service.deleteOrganization(organizationId, confirmation, purgeHistory);
    }

    @GetMapping("/users")
    public List<PlatformAdministrationService.PlatformUserView> users(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return service.users();
    }

    @PatchMapping("/users/{userId}")
    public PlatformAdministrationService.PlatformUserView setUserEnabled(HttpServletRequest request,
                                                                           @PathVariable UUID userId,
                                                                           @Valid @RequestBody UserStatus body) {
        requirePlatformAdmin(request);
        service.setUserEnabled(userId, body.enabled());
        return service.users().stream().filter(user -> user.id().equals(userId)).findFirst().orElseThrow();
    }

    @DeleteMapping("/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(HttpServletRequest request, @PathVariable UUID userId, @RequestParam String confirmation) {
        requirePlatformAdmin(request);
        service.deleteUser(userId, confirmation);
    }

    @GetMapping("/teams")
    public List<PlatformAdministrationService.PlatformTeamView> teams(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return service.teams();
    }

    @DeleteMapping("/teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTeam(HttpServletRequest request, @PathVariable UUID teamId, @RequestParam String confirmation) {
        requirePlatformAdmin(request);
        service.deleteTeam(teamId, confirmation);
    }
    @GetMapping("/api-keys")
    public List<PlatformAdministrationService.PlatformApiKeyView> apiKeys(HttpServletRequest request) {
        requirePlatformAdmin(request);
        return service.apiKeys();
    }

    @DeleteMapping("/api-keys/{apiKeyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApiKey(HttpServletRequest request, @PathVariable UUID apiKeyId) {
        requirePlatformAdmin(request);
        service.deleteApiKey(apiKeyId);
    }

    private void requirePlatformAdmin(HttpServletRequest request) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (!Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin")) && (actor == null || !actor.platformAdmin())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED", "Platform administrator permission is required.");
        }
    }

    public record UserStatus(boolean enabled) { }
}
