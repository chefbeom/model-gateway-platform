package com.aiconnect.llmgateway.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PatchMapping;

import java.util.UUID;

/** Project owner, team administrator, organization administrator, and platform administrator emergency actions. */
@RestController
@RequestMapping("/api/admin/projects")
public class ProjectControlController {
    private final ProjectControlService control;

    public ProjectControlController(ProjectControlService control) {
        this.control = control;
    }

    @GetMapping("/{projectId}/control")
    public ProjectControlService.ProjectControlView control(@PathVariable UUID projectId) {
        return control.control(projectId);
    }

    @PatchMapping("/{projectId}/status")
    public ProjectControlService.ProjectControlView changeStatus(@PathVariable UUID projectId,
                                                                  @Valid @RequestBody UpdateProjectStatus request) {
        return control.changeStatus(projectId, request.status(), request.revokeActiveApiKeys());
    }

    @PostMapping("/{projectId}/api-keys/{apiKeyId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(@PathVariable UUID projectId, @PathVariable UUID apiKeyId) {
        control.revokeApiKey(projectId, apiKeyId);
    }

    public record UpdateProjectStatus(@NotBlank String status, boolean revokeActiveApiKeys) { }
}
