package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.ProjectServiceAccess;
import com.aiconnect.llmgateway.domain.ProjectServiceAccessId;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.ProjectServiceAccessRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Manages the project-to-logical-service relationship independently from project deletion. */
@Service
public class ProjectServiceAccessManagementService {
    private final ProjectRepository projects;
    private final ProjectServiceAccessRepository access;
    private final LlmServiceRepository services;

    public ProjectServiceAccessManagementService(ProjectRepository projects, ProjectServiceAccessRepository access,
                                                  LlmServiceRepository services) {
        this.projects = projects;
        this.access = access;
        this.services = services;
    }

    @Transactional(readOnly = true)
    public List<ServiceAccessView> list(UUID projectId) {
        requireProject(projectId);
        return access.findByIdProjectId(projectId).stream()
                .map(ProjectServiceAccess::getId)
                .map(id -> services.findById(id.getServiceId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(ServiceAccessView::from)
                .sorted(Comparator.comparing(ServiceAccessView::serviceKey, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public void revoke(UUID projectId, UUID serviceId) {
        requireProject(projectId);
        ProjectServiceAccessId id = new ProjectServiceAccessId(projectId, serviceId);
        ProjectServiceAccess relation = access.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "PROJECT_SERVICE_ACCESS_NOT_FOUND", "This logical service is not granted to the project."));
        access.delete(relation);
        access.flush();
    }

    private Project requireProject(UUID projectId) {
        return projects.findById(projectId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "PROJECT_NOT_FOUND", "The project does not exist."));
    }

    public record ServiceAccessView(UUID id, String serviceKey, String displayName, boolean enabled) {
        static ServiceAccessView from(LlmService service) {
            return new ServiceAccessView(service.getId(), service.getServiceKey(), service.getDisplayName(), service.isEnabled());
        }
    }
}
