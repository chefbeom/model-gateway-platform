package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ProjectServiceAccessManagementService serviceAccess;
    @Autowired(required = false)
    private TeamAccessService teamAccess;
    @Autowired(required = false)
    private ProjectRepository projects;

    public ProjectServiceAccessManagementController(ProjectServiceAccessManagementService serviceAccess) {
        this.serviceAccess = serviceAccess;
    }

    @GetMapping
    public List<ProjectServiceAccessManagementService.ServiceAccessView> list(@PathVariable UUID projectId) {
        return serviceAccess.list(projectId);
    }

    @DeleteMapping("/{serviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID projectId, @PathVariable UUID serviceId, HttpServletRequest http) {
        requirePlatformOrOrgAdmin(http, projectId);
        serviceAccess.revoke(projectId, serviceId);
    }

    private void requirePlatformOrOrgAdmin(HttpServletRequest http, UUID projectId) {
        if (http == null || teamAccess == null || projects == null) return;
        if (Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin"))) return;
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (actor != null && actor.platformAdmin()) return;
        Project project = projects.findById(projectId).orElse(null);
        if (project == null) return;
        if (!teamAccess.isOrganizationAdmin(actor, project.getOrganizationId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_ADMIN_REQUIRED",
                    "Only an organization or platform administrator may revoke project service access.");
        }
    }
}
