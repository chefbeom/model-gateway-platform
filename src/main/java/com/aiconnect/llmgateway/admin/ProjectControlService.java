package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.ApiKeyStatus;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/** Emergency control for a project. Suspending blocks every API key at the gateway boundary. */
@Service
public class ProjectControlService {
    private final ProjectRepository projects;
    private final ApiKeyRepository apiKeys;
    private final EntityManager entityManager;

    public ProjectControlService(ProjectRepository projects, ApiKeyRepository apiKeys, EntityManager entityManager) {
        this.projects = projects;
        this.apiKeys = apiKeys;
        this.entityManager = entityManager;
    }

    @Transactional
    public ProjectControlView changeStatus(UUID projectId, String requestedStatus, boolean revokeActiveApiKeys) {
        Project project = requireProject(projectId);
        String status = normalizeStatus(requestedStatus);
        long revoked = 0;
        if ("SUSPENDED".equals(status) && revokeActiveApiKeys) {
            for (ApiKey key : apiKeys.findByProjectId(projectId)) {
                if (key.getStatus() == ApiKeyStatus.ACTIVE) {
                    key.revoke();
                    revoked++;
                }
            }
        }
        entityManager.createQuery("update Project p set p.status = :status where p.id = :projectId")
                .setParameter("status", status)
                .setParameter("projectId", projectId)
                .executeUpdate();
        return view(project, status, revoked);
    }

    @Transactional
    public void revokeApiKey(UUID projectId, UUID apiKeyId) {
        requireProject(projectId);
        ApiKey key = apiKeys.findById(apiKeyId).filter(candidate -> candidate.getProjectId().equals(projectId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "이 프로젝트의 API 키를 찾을 수 없습니다."));
        key.revoke();
    }

    @Transactional(readOnly = true)
    public ProjectControlView control(UUID projectId) {
        Project project = requireProject(projectId);
        return view(project, project.getStatus(), 0);
    }

    private ProjectControlView view(Project project, String status, long revoked) {
        long activeKeys = apiKeys.findByProjectId(project.getId()).stream().filter(key -> key.getStatus() == ApiKeyStatus.ACTIVE).count();
        return new ProjectControlView(project.getId(), project.getName(), status, activeKeys, revoked);
    }

    private Project requireProject(UUID projectId) {
        return projects.findById(projectId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "PROJECT_NOT_FOUND", "프로젝트를 찾을 수 없습니다."));
    }

    private String normalizeStatus(String value) {
        String status = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROJECT_STATUS", "프로젝트 상태는 ACTIVE 또는 SUSPENDED만 사용할 수 있습니다.");
        }
        return status;
    }

    public record ProjectControlView(UUID projectId, String projectName, String status, long activeApiKeyCount,
                                     long revokedApiKeyCount) { }
}
