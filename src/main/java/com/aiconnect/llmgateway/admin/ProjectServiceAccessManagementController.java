package com.aiconnect.llmgateway.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/service-access")
public class ProjectServiceAccessManagementController {
    private final ProjectServiceAccessManagementService access;

    public ProjectServiceAccessManagementController(ProjectServiceAccessManagementService access) {
        this.access = access;
    }

    @GetMapping
    public List<ProjectServiceAccessManagementService.ServiceAccessView> list(@PathVariable UUID projectId) {
        return access.list(projectId);
    }

    @DeleteMapping("/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID projectId, @PathVariable UUID serviceId) {
        access.revoke(projectId, serviceId);
    }
}
