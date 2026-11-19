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
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.repository.ProjectRepository;

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
        boolean platform = Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin"));
        boolean orgAdmin = projects.findById(projectId).map(project -> access.isOrganizationAdmin(CurrentActor.principal().orElse(null), project.getOrganizationId())).orElse(false);
        return lifecycle.update(projectId, request.name(), request.teamId(), request.externalAiBlocked(), platform || orgAdmin);
    }

    /** POST intentionally requires project-management permission; it does not mutate data. */
    @PostMapping("/{projectId}/deletion-preview")
    public ProjectLifecycleService.ProjectDeletionPreview deletionPreview(@PathVariable UUID projectId) {
        return lifecycle.deletionPreview(projectId);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId) {
        lifecycle.delete(projectId);
    }

    public record UpdateProject(@NotBlank @Size(max = 120) String name, UUID teamId, Boolean externalAiBlocked) { }
}
