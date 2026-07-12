package com.aiconnect.llmgateway.external;

import com.aiconnect.llmgateway.domain.ExternalAccessStatus;
import com.aiconnect.llmgateway.identity.*;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class ProjectExternalAccessController {
    private final ProjectExternalAccessService service;
    private final TeamAccessService access;
    public ProjectExternalAccessController(ProjectExternalAccessService service, TeamAccessService access) {
        this.service = service; this.access = access;
    }

    @GetMapping("/api/portal/projects/{projectId}/external-access")
    public List<ProjectExternalAccessService.PortalAccessView> portal(@PathVariable UUID projectId) {
        AuthPrincipal actor = actor();
        if (!access.canViewProject(actor, projectId)) throw denied();
        return service.portal(projectId);
    }

    @PostMapping("/api/portal/projects/{projectId}/external-access")
    public ProjectExternalAccessService.PortalAccessView request(@PathVariable UUID projectId,
                                                                  @Valid @RequestBody RequestAccess request) {
        AuthPrincipal actor = actor();
        if (!access.canViewProject(actor, projectId)) throw denied();
        return service.request(projectId, request.providerId(), actor.userId(), request.reason());
    }

    @GetMapping("/api/admin/organizations/{organizationId}/external-access")
    public List<ProjectExternalAccessService.AdminAccessView> organization(@PathVariable UUID organizationId) {
        return service.organization(organizationId);
    }

    @PatchMapping("/api/admin/projects/{projectId}/external-access/{providerId}")
    public ProjectExternalAccessService.AdminAccessView decide(@PathVariable UUID projectId, @PathVariable UUID providerId,
                                                                 @Valid @RequestBody Decision request) {
        return service.decide(projectId, providerId, request.status(), request.manualAllowed(),
                request.autoFailoverEnabled(), request.monthlyCostLimit(), request.expiresAt(), CurrentActor.userIdOrNull());
    }

    private AuthPrincipal actor() { return CurrentActor.principal().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Login is required.")); }
    private ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED", "The current user cannot access this project."); }

    public record RequestAccess(@NotNull UUID providerId, @NotBlank @Size(max = 1000) String reason) { }
    public record Decision(@NotNull ExternalAccessStatus status, boolean manualAllowed, boolean autoFailoverEnabled,
                           @Positive BigDecimal monthlyCostLimit, Instant expiresAt) { }
}
