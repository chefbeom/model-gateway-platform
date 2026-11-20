package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.DataBoundaryPolicy;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.ProjectServiceAccess;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.ProjectServiceAccessRepository;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Project metadata changes and safe permanent deletion for projects without request history. */
@Service
public class ProjectLifecycleService {
    private final ProjectRepository projects;
    private final TeamRepository teams;
    private final ApiKeyRepository apiKeys;
    private final ProjectServiceAccessRepository serviceAccess;
    private final LlmServiceRepository services;
    private final LlmRequestRepository requests;
    private final AuditService audit;
    private final EntityManager entityManager;

    public ProjectLifecycleService(ProjectRepository projects, TeamRepository teams, ApiKeyRepository apiKeys,
                                   ProjectServiceAccessRepository serviceAccess, LlmServiceRepository services,
                                   LlmRequestRepository requests, AuditService audit, EntityManager entityManager) {
        this.projects = projects;
        this.teams = teams;
        this.apiKeys = apiKeys;
        this.serviceAccess = serviceAccess;
        this.services = services;
        this.requests = requests;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional
    public ProjectView update(UUID projectId, String name, UUID teamId, Boolean externalAiBlocked) {
        return update(projectId, name, teamId, externalAiBlocked, null, null, true);
    }

    @Transactional
    public ProjectView update(UUID projectId, String name, UUID teamId, Boolean externalAiBlocked, boolean mayClearBoundary) {
        return update(projectId, name, teamId, externalAiBlocked, null, null, mayClearBoundary);
    }

    @Transactional
    public ProjectView update(UUID projectId, String name, UUID teamId, Boolean externalAiBlocked,
                              DataBoundaryPolicy dataBoundaryPolicy, DataBoundaryPolicy maxDataBoundaryPolicy,
                              boolean mayClearBoundary) {
        Project project = entityManager.find(Project.class, projectId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (project == null) throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist.");

        DataBoundaryPolicy currentCeiling = project.getMaxDataBoundaryPolicy();
        DataBoundaryPolicy currentEffective = project.effectiveDataBoundaryPolicy();

        if (!mayClearBoundary) {
            if (maxDataBoundaryPolicy != null && maxDataBoundaryPolicy != currentCeiling) {
                throw new ApiException(HttpStatus.FORBIDDEN, "DATA_BOUNDARY_RELAXATION_DENIED",
                        "Only an organization or platform administrator may change the project maximum data boundary ceiling.");
            }
            if (dataBoundaryPolicy != null && dataBoundaryPolicy.exceeds(currentCeiling)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "DATA_BOUNDARY_RELAXATION_DENIED",
                        "Project data boundary cannot exceed the administrator ceiling.");
            }
            if (project.isExternalAiBlocked()
                    && ((dataBoundaryPolicy == null && Boolean.FALSE.equals(externalAiBlocked))
                    || (dataBoundaryPolicy != null && dataBoundaryPolicy != DataBoundaryPolicy.INTERNAL_ONLY))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "DATA_BOUNDARY_RELAXATION_DENIED",
                        "Only an organization administrator may disable the external AI boundary.");
            }
        }

        DataBoundaryPolicy nextCeiling = currentCeiling;
        DataBoundaryPolicy nextPolicy = currentEffective;

        if (maxDataBoundaryPolicy != null) {
            nextCeiling = maxDataBoundaryPolicy;
        } else if (mayClearBoundary && Boolean.FALSE.equals(externalAiBlocked) && dataBoundaryPolicy == null
                && currentCeiling == DataBoundaryPolicy.INTERNAL_ONLY) {
            nextCeiling = DataBoundaryPolicy.EXTERNAL_ALLOWED;
        } else if (mayClearBoundary && dataBoundaryPolicy != null && dataBoundaryPolicy.exceeds(nextCeiling)) {
            nextCeiling = dataBoundaryPolicy;
        } else if (mayClearBoundary && Boolean.TRUE.equals(externalAiBlocked) && dataBoundaryPolicy == null) {
            nextCeiling = DataBoundaryPolicy.INTERNAL_ONLY;
        }

        if (dataBoundaryPolicy != null) {
            if (dataBoundaryPolicy.exceeds(nextCeiling)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "DATA_BOUNDARY_RELAXATION_DENIED",
                        "Project data boundary cannot exceed maxDataBoundaryPolicy.");
            }
            nextPolicy = dataBoundaryPolicy;
        } else if (Boolean.TRUE.equals(externalAiBlocked)) {
            nextPolicy = DataBoundaryPolicy.INTERNAL_ONLY;
        } else if (Boolean.FALSE.equals(externalAiBlocked)) {
            nextPolicy = nextCeiling;
        }

        if (nextPolicy.exceeds(nextCeiling)) {
            nextPolicy = nextCeiling;
        }

        String normalizedName = name.trim();
        if (teamId != null) requireTeam(project.getOrganizationId(), teamId);
        project.updateMetadata(normalizedName, teamId);
        project.configureDataBoundaryPolicy(nextPolicy, nextCeiling);
        Project updated = projects.saveAndFlush(project);
        audit.record(updated.getOrganizationId(), CurrentActor.userIdOrNull(), "PROJECT_UPDATED", "PROJECT", projectId,
                Map.of("name", normalizedName, "teamId", String.valueOf(teamId),
                        "externalAiBlocked", updated.isExternalAiBlocked(),
                        "dataBoundaryPolicy", updated.getDataBoundaryPolicy().name(),
                        "maxDataBoundaryPolicy", updated.getMaxDataBoundaryPolicy().name()));
        return ProjectView.from(updated);
    }

    @Transactional(readOnly = true)
    public ProjectDeletionPreview deletionPreview(UUID projectId) {
        Project project = requireProject(projectId);
        List<ApiKey> keys = apiKeys.findByProjectId(projectId);
        List<ServiceReference> projectServices = serviceAccess.findByIdProjectId(projectId).stream()
                .map(ProjectServiceAccess::getId)
                .map(access -> services.findById(access.getServiceId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(ServiceReference::from)
                .sorted(Comparator.comparing(ServiceReference::serviceKey, String.CASE_INSENSITIVE_ORDER))
                .toList();
        long requestCount = requests.countByProjectId(projectId);
        boolean deletable = requestCount == 0;
        String reason = deletable
                ? "API 키와 서비스 권한 설정을 함께 삭제한 뒤 프로젝트를 영구 삭제합니다."
                : "요청·사용량 이력이 있는 프로젝트는 감사 기록을 보존하기 위해 삭제할 수 없습니다. 프로젝트를 중지한 뒤 보존하세요.";
        return new ProjectDeletionPreview(projectId, project.getName(), project.getStatus(),
                project.getTeamId(), keys.stream().map(ApiKeyReference::from).toList(), projectServices,
                requestCount, deletable, reason);
    }

    @Transactional
    public void delete(UUID projectId) {
        Project project = requireProject(projectId);
        long requestCount = requests.countByProjectId(projectId);
        if (requestCount > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_HAS_REQUEST_HISTORY",
                    "Projects with request history cannot be permanently deleted. Suspend the project to preserve audit and usage records.");
        }
        int keyCount = apiKeys.findByProjectId(projectId).size();
        int serviceCount = serviceAccess.findByIdProjectId(projectId).size();

        executeDelete("delete from usage_alert_delivery where project_id = :projectId", projectId);
        executeDelete("delete from usage_alert_state where project_id = :projectId", projectId);
        executeDelete("delete from project_alert_policy where project_id = :projectId", projectId);
        executeDelete("delete from project_content_policy where project_id = :projectId", projectId);
        executeDelete("delete from project_quota where project_id = :projectId", projectId);
        executeDelete("delete from project_external_access where project_id = :projectId", projectId);
        executeDelete("delete from project_service_access where project_id = :projectId", projectId);
        executeDelete("delete from api_key where project_id = :projectId", projectId);
        executeDelete("delete from project where id = :projectId", projectId);

        audit.record(project.getOrganizationId(), CurrentActor.userIdOrNull(), "PROJECT_DELETED", "PROJECT", projectId,
                Map.of("name", project.getName(), "deletedApiKeyCount", keyCount, "removedServiceAccessCount", serviceCount));
    }

    private void executeDelete(String sql, UUID projectId) {
        entityManager.createNativeQuery(sql).setParameter("projectId", projectId).executeUpdate();
    }

    private Project requireProject(UUID projectId) {
        return projects.findById(projectId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
    }

    private void requireTeam(UUID organizationId, UUID teamId) {
        Team team = teams.findById(teamId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "The team does not exist."));
        if (!organizationId.equals(team.getOrganizationId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "The team belongs to another organization.");
        }
    }

    public record ProjectView(UUID id, UUID organizationId, UUID teamId, String name, String status,
                              boolean externalAiBlocked, DataBoundaryPolicy dataBoundaryPolicy,
                              DataBoundaryPolicy maxDataBoundaryPolicy) {
        static ProjectView from(Project project) {
            return new ProjectView(project.getId(), project.getOrganizationId(), project.getTeamId(),
                    project.getName(), project.getStatus(), project.isExternalAiBlocked(),
                    project.getDataBoundaryPolicy(), project.getMaxDataBoundaryPolicy());
        }
    }
    public record ApiKeyReference(UUID id, String name, String keyPrefix, String status) {
        static ApiKeyReference from(ApiKey key) { return new ApiKeyReference(key.getId(), key.getName(), key.getKeyPrefix(), key.getStatus().name()); }
    }
    public record ServiceReference(UUID id, String serviceKey, String displayName) {
        static ServiceReference from(LlmService service) { return new ServiceReference(service.getId(), service.getServiceKey(), service.getDisplayName()); }
    }
    public record ProjectDeletionPreview(UUID projectId, String projectName, String status, UUID teamId,
                                         List<ApiKeyReference> apiKeys, List<ServiceReference> services,
                                         long requestHistoryCount, boolean deletable, String reason) { }
}
