package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PatchMapping;

import java.util.Locale;
import java.util.UUID;

/** Project owner, team administrator, organization administrator, and platform administrator emergency actions. */
@RestController
@RequestMapping("/api/admin/projects")
public class ProjectControlController {
    private final ProjectControlService control;
    @Autowired(required = false)
    private TeamAccessService access;
    @Autowired(required = false)
    private ProjectRepository projects;

    public ProjectControlController(ProjectControlService control) {
        this.control = control;
    }

    @GetMapping("/{projectId}/control")
    public ProjectControlService.ProjectControlView control(@PathVariable UUID projectId) {
        return control.control(projectId);
    }

    @PatchMapping("/{projectId}/status")
    public ProjectControlService.ProjectControlView changeStatus(@PathVariable UUID projectId,
                                                                  @Valid @RequestBody UpdateProjectStatus request,
                                                                  HttpServletRequest http) {
        String normalized = request.status() == null ? "" : request.status().trim().toUpperCase(Locale.ROOT);
        if ("ACTIVE".equals(normalized)) {
            requireOrganizationOrPlatformAdmin(http, projectId);
        }
        return control.changeStatus(projectId, request.status(), request.revokeActiveApiKeys());
    }

    private void requireOrganizationOrPlatformAdmin(HttpServletRequest http, UUID projectId) {
        if (http == null || access == null || projects == null) return;
        if (Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin"))) return;
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (actor != null && actor.platformAdmin()) return;
        Project project = projects.findById(projectId).orElse(null);
        if (project == null) return;
        if (!access.isOrganizationAdmin(actor, project.getOrganizationId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_ADMIN_REQUIRED",
                    "Only an organization or platform administrator may resume a suspended project.");
        }
    }

    @PostMapping("/{projectId}/api-keys/{apiKeyId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(@PathVariable UUID projectId, @PathVariable UUID apiKeyId) {
        control.revokeApiKey(projectId, apiKeyId);
    }

    public record UpdateProjectStatus(@NotBlank String status, boolean revokeActiveApiKeys) { }
}
