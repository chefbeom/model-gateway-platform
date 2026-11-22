package com.aiconnect.llmgateway.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import com.aiconnect.llmgateway.domain.DataBoundaryPolicy;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.web.ApiException;

@RestController
@RequestMapping("/api/admin/projects")
public class ProjectLifecycleController {
    private final ProjectLifecycleService lifecycle;
    private final TeamAccessService access;
    private final ProjectRepository projects;

    public ProjectLifecycleController(ProjectLifecycleService lifecycle, TeamAccessService access, ProjectRepository projects) {
        this.lifecycle = lifecycle;
        this.access = access;
        this.projects = projects;
    }

    @PatchMapping("/{projectId}")
    public ProjectLifecycleService.ProjectView update(@PathVariable UUID projectId, @Valid @RequestBody UpdateProject request, HttpServletRequest http) {
        return lifecycle.update(projectId, request.name(), request.teamId(), request.externalAiBlocked(),
                request.dataBoundaryPolicy(), request.maxDataBoundaryPolicy(), isOrganizationOrPlatformAdmin(http, projectId));
    }

    /** POST requires organization or platform administrator permission; it does not mutate data. */
    @PostMapping("/{projectId}/deletion-preview")
    public ProjectLifecycleService.ProjectDeletionPreview deletionPreview(@PathVariable UUID projectId, HttpServletRequest http) {
        requireOrganizationOrPlatformAdmin(http, projectId);
        return lifecycle.deletionPreview(projectId);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, HttpServletRequest http) {
        requireOrganizationOrPlatformAdmin(http, projectId);
        lifecycle.delete(projectId);
    }

    private void requireOrganizationOrPlatformAdmin(HttpServletRequest http, UUID projectId) {
        if (!isOrganizationOrPlatformAdmin(http, projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_ADMIN_REQUIRED",
                    "Only an organization or platform administrator may perform project lifecycle deletion.");
        }
    }

    private boolean isOrganizationOrPlatformAdmin(HttpServletRequest http, UUID projectId) {
        if (http != null && Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin"))) {
            return true;
        }
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (actor != null && actor.platformAdmin()) {
            return true;
        }
        Project project = projects.findById(projectId).orElse(null);
        if (project == null) {
            // Preserve 404 PROJECT_NOT_FOUND from the service when project does not exist.
            return true;
        }
        return access.isOrganizationAdmin(actor, project.getOrganizationId());
    }

    public record UpdateProject(@NotBlank @Size(max = 120) String name, UUID teamId, Boolean externalAiBlocked,
                                DataBoundaryPolicy dataBoundaryPolicy, DataBoundaryPolicy maxDataBoundaryPolicy) {
        public UpdateProject(String name, UUID teamId, Boolean externalAiBlocked) {
            this(name, teamId, externalAiBlocked, null, null);
        }
    }
}
