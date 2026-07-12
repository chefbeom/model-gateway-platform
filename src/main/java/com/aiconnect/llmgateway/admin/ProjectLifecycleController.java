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

@RestController
@RequestMapping("/api/admin/projects")
public class ProjectLifecycleController {
    private final ProjectLifecycleService lifecycle;

    public ProjectLifecycleController(ProjectLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PatchMapping("/{projectId}")
    public ProjectLifecycleService.ProjectView update(@PathVariable UUID projectId, @Valid @RequestBody UpdateProject request) {
        return lifecycle.update(projectId, request.name(), request.teamId());
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

    public record UpdateProject(@NotBlank @Size(max = 120) String name, UUID teamId) { }
}
