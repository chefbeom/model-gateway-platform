package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.identity.AppUser;
import com.aiconnect.llmgateway.identity.AppUserRepository;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.identity.OrganizationMemberRepository;
import com.aiconnect.llmgateway.identity.OrganizationAccessService;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Global, explicitly audited Platform administrator operations. */
@Service
public class PlatformAdministrationService {
    private final OrganizationRepository organizations;
    private final ProjectRepository projects;
    private final ExternalProviderRepository providers;
    private final InferenceNodeRepository nodes;
    private final RuntimeEndpointRepository endpoints;
    private final ModelDeploymentRepository deployments;
    private final LlmServiceRepository services;
    private final ApiKeyRepository apiKeys;
    private final AppUserRepository users;
    private final OrganizationMemberRepository organizationMembers;
    private final TeamRepository teams;
    private final OrganizationAccessService organizationAccess;
    private final LlmRequestRepository requests;
    private final AuditService audit;
    private final EntityManager entityManager;

    public PlatformAdministrationService(OrganizationRepository organizations, ProjectRepository projects,
            ExternalProviderRepository providers, InferenceNodeRepository nodes, RuntimeEndpointRepository endpoints,
            ModelDeploymentRepository deployments, LlmServiceRepository services, ApiKeyRepository apiKeys,
            AppUserRepository users, OrganizationMemberRepository organizationMembers, TeamRepository teams,
            OrganizationAccessService organizationAccess, LlmRequestRepository requests, AuditService audit, EntityManager entityManager) {
        this.organizations = organizations;
        this.projects = projects;
        this.providers = providers;
        this.nodes = nodes;
        this.endpoints = endpoints;
        this.deployments = deployments;
        this.services = services;
        this.apiKeys = apiKeys;
        this.users = users;
        this.organizationMembers = organizationMembers;
        this.teams = teams;
        this.organizationAccess = organizationAccess;
        this.requests = requests;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PlatformOverview overview() {
        return new PlatformOverview(organizations.count(), users.count(), projects.count(), providers.count(),
                nodes.count(), endpoints.count(), deployments.count(), services.count(), apiKeys.count(), requests.count());
    }

    @Transactional(readOnly = true)
    public List<OrganizationSummary> organizations() {
        return organizations.findAll().stream().map(org -> {
            UUID id = org.getId();
            return new OrganizationSummary(id, org.getName(), org.getStatus(), projects.findByOrganizationId(id).size(),
                    providers.findByOrganizationIdOrderByDisplayNameAsc(id).size(), nodes.findByOrganizationId(id).size(),
                    organizationMembers.findByIdOrganizationId(id).size());
        }).toList();
    }

    @Transactional(readOnly = true)
    public OrganizationCleanupPreview cleanupPreview(UUID organizationId) {
        Organization org = requireOrganization(organizationId);
        var orgProjects = projects.findByOrganizationId(organizationId);
        var orgNodes = nodes.findByOrganizationId(organizationId);
        int endpointCount = orgNodes.stream().mapToInt(n -> endpoints.findByNodeId(n.getId()).size()).sum();
        long requestCount = orgProjects.stream().mapToLong(p -> requests.countByProjectId(p.getId())).sum();
        return new OrganizationCleanupPreview(organizationId, org.getName(), org.getStatus(), orgProjects.size(),
                providers.findByOrganizationIdOrderByDisplayNameAsc(organizationId).size(), orgNodes.size(), endpointCount,
                organizationMembers.findByIdOrganizationId(organizationId).size(), requestCount,
                "프로젝트·Runtime·Provider·모델·요청 이력을 정리합니다. 사용자 계정 자체는 삭제하지 않습니다.");
    }

    @Transactional
    public void suspendOrganization(UUID organizationId) {
        Organization org = requireOrganization(organizationId);
        execute("update organization set status = 'SUSPENDED' where id = :id", organizationId);
        audit.record(organizationId, CurrentActor.userIdOrNull(), "ORGANIZATION_SUSPENDED", "ORGANIZATION", organizationId,
                Map.of("name", org.getName()));
    }

    @Transactional
    public void restoreOrganization(UUID organizationId) {
        Organization org = requireOrganization(organizationId);
        execute("update organization set status = 'ACTIVE' where id = :id", organizationId);
        audit.record(organizationId, CurrentActor.userIdOrNull(), "ORGANIZATION_RESTORED", "ORGANIZATION", organizationId,
                Map.of("name", org.getName()));
    }

    @Transactional
    public void deleteOrganization(UUID organizationId, String confirmation, boolean purgeHistory) {
        Organization org = requireOrganization(organizationId);
        if (!org.getName().equals(confirmation)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRMATION_MISMATCH", "조직 이름을 정확히 입력해야 합니다.");
        }
        if (!purgeHistory) {
            throw new ApiException(HttpStatus.CONFLICT, "PURGE_CONFIRMATION_REQUIRED", "영구 삭제에는 purgeHistory=true가 필요합니다.");
        }
        OrganizationCleanupPreview preview = cleanupPreview(organizationId);
        execute("delete from llm_request_content where request_id in (select id from llm_request where project_id in (select id from project where organization_id = :id))", organizationId);
        execute("delete from llm_request_attempt where request_id in (select id from llm_request where project_id in (select id from project where organization_id = :id))", organizationId);
        execute("delete from llm_request where project_id in (select id from project where organization_id = :id)", organizationId);
        execute("delete from notification_delivery where incident_id in (select i.id from incident i join runtime_endpoint e on e.id=i.runtime_endpoint_id join inference_node n on n.id=e.node_id where n.organization_id=:id)", organizationId);
        execute("delete from usage_alert_delivery where project_id in (select id from project where organization_id = :id)", organizationId);
        execute("delete from usage_alert_state where project_id in (select id from project where organization_id = :id)", organizationId);
        execute("delete from project_alert_policy where project_id in (select id from project where organization_id = :id)", organizationId);
        execute("delete from project_content_policy where project_id in (select id from project where organization_id = :id)", organizationId);
        execute("delete from project_quota where project_id in (select id from project where organization_id = :id)", organizationId);
        execute("delete from project_external_access where project_id in (select id from project where organization_id = :id)", organizationId);
        execute("delete from service_target where service_id in (select id from llm_service where organization_id = :id)", organizationId);
        execute("delete from service_target where deployment_id in (select d.id from model_deployment d join runtime_endpoint e on e.id=d.runtime_endpoint_id join inference_node n on n.id=e.node_id where n.organization_id=:id)", organizationId);
        execute("delete from project_service_access where service_id in (select id from llm_service where organization_id = :id)", organizationId);
        execute("delete from llm_service where organization_id = :id", organizationId);
        execute("delete from service_target where deployment_id in (select id from model_deployment where external_provider_id in (select id from external_provider where organization_id = :id))", organizationId);
        execute("delete from model_deployment where external_provider_id in (select id from external_provider where organization_id = :id)", organizationId);
        execute("delete from runtime_model_operation where runtime_endpoint_id in (select e.id from runtime_endpoint e join inference_node n on n.id=e.node_id where n.organization_id=:id)", organizationId);
        execute("delete from runtime_model_profile where runtime_endpoint_id in (select e.id from runtime_endpoint e join inference_node n on n.id=e.node_id where n.organization_id=:id)", organizationId);
        execute("delete from model_deployment where runtime_endpoint_id in (select e.id from runtime_endpoint e join inference_node n on n.id=e.node_id where n.organization_id=:id)", organizationId);
        execute("delete from project_external_access where provider_id in (select id from external_provider where organization_id = :id)", organizationId);
        execute("delete from incident where runtime_endpoint_id in (select e.id from runtime_endpoint e join inference_node n on n.id=e.node_id where n.organization_id=:id)", organizationId);
        execute("delete from notification_channel where organization_id = :id", organizationId);
        execute("delete from runtime_endpoint where node_id in (select id from inference_node where organization_id = :id)", organizationId);
        execute("delete from external_provider where organization_id = :id", organizationId);
        execute("delete from api_key where project_id in (select id from project where organization_id = :id)", organizationId);
        execute("delete from project where organization_id = :id", organizationId);
        execute("delete from team_member where team_id in (select id from team where organization_id = :id)", organizationId);
        execute("delete from team where organization_id = :id", organizationId);
        execute("delete from organization_member where organization_id = :id", organizationId);
        execute("delete from audit_log where organization_id = :id", organizationId);
        execute("delete from accelerator_device where node_id in (select id from inference_node where organization_id = :id)", organizationId);
        execute("delete from inference_node where organization_id = :id", organizationId);
        execute("delete from organization where id = :id", organizationId);
        audit.record(null, CurrentActor.userIdOrNull(), "ORGANIZATION_PURGED", "ORGANIZATION", organizationId,
                Map.of("name", org.getName(), "projectCount", preview.projectCount(), "requestCount", preview.requestCount()));
    }

    @Transactional(readOnly = true)
    public List<PlatformUserView> users() {
        return users.findAll().stream().map(user -> new PlatformUserView(user.getId(), user.getEmail(), user.isPlatformAdmin(),
                user.isEnabled(), organizationMembers.findByIdUserId(user.getId()).size(), apiKeys.findByIssuedByUserId(user.getId()).size())).toList();
    }

    @Transactional(readOnly = true)
    public List<PlatformTeamView> teams() {
        Map<UUID, String> organizationNames = organizations.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Organization::getId, Organization::getName));
        return teams.findAll().stream().map(team -> new PlatformTeamView(team.getId(), team.getOrganizationId(),
                organizationNames.getOrDefault(team.getOrganizationId(), "(deleted organization)"), team.getName(),
                team.getStatus(), projects.findByTeamId(team.getId()).size())).toList();
    }

    @Transactional
    public void deleteTeam(UUID teamId, String confirmation) {
        Team team = teams.findById(teamId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "The team does not exist."));
        if (!team.getName().equals(confirmation)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRMATION_MISMATCH", "팀 이름을 정확히 입력해야 합니다.");
        }
        organizationAccess.deleteTeam(team.getOrganizationId(), teamId);
    }

    @Transactional(readOnly = true)
    public List<PlatformApiKeyView> apiKeys() {
        Map<UUID, com.aiconnect.llmgateway.domain.Project> projectById = projects.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(com.aiconnect.llmgateway.domain.Project::getId, project -> project));
        Map<UUID, Organization> organizationById = organizations.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Organization::getId, org -> org));
        return apiKeys.findAll().stream().map(key -> {
            var project = projectById.get(key.getProjectId());
            var org = project == null ? null : organizationById.get(project.getOrganizationId());
            return new PlatformApiKeyView(key.getId(), key.getProjectId(), project == null ? "(deleted project)" : project.getName(),
                    org == null ? "(deleted organization)" : org.getName(), key.getName(), key.getKeyPrefix(),
                    key.getStatus().name(), key.getExpiresAt(), key.getLastUsedAt(), key.getCreatedAt());
        }).toList();
    }

    @Transactional
    public void setUserEnabled(UUID userId, boolean enabled) {
        AppUser user = users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        execute("update app_user set enabled = :enabled where id = :id", enabled, userId);
        audit.record(null, CurrentActor.userIdOrNull(), enabled ? "PLATFORM_USER_ENABLED" : "PLATFORM_USER_DISABLED", "APP_USER", userId, Map.of("email", user.getEmail()));
    }

    @Transactional
    public void deleteUser(UUID userId, String confirmation) {
        AppUser user = users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        if (!user.getEmail().equalsIgnoreCase(confirmation)) throw new ApiException(HttpStatus.BAD_REQUEST, "CONFIRMATION_MISMATCH", "사용자 이메일을 정확히 입력해야 합니다.");
        if (userId.equals(CurrentActor.userIdOrNull())) throw new ApiException(HttpStatus.CONFLICT, "SELF_DELETE_FORBIDDEN", "현재 관리자 계정은 삭제할 수 없습니다.");
        execute("update audit_log set actor_user_id = null where actor_user_id = :id", userId);
        execute("update api_key set issued_by_user_id = null where issued_by_user_id = :id", userId);
        execute("delete from auth_refresh_token where user_id = :id", userId);
        execute("delete from team_member where user_id = :id", userId);
        execute("delete from organization_member where user_id = :id", userId);
        execute("delete from app_user where id = :id", userId);
    }

    @Transactional
    public void deleteApiKey(UUID apiKeyId) {
        ApiKey key = apiKeys.findById(apiKeyId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "API 키를 찾을 수 없습니다."));
        execute("delete from api_key where id = :id", apiKeyId);
        audit.record(null, CurrentActor.userIdOrNull(), "PLATFORM_API_KEY_DELETED", "API_KEY", apiKeyId, Map.of("permanent", true, "prefix", key.getKeyPrefix()));
    }

    private void execute(String sql, UUID id) { entityManager.createNativeQuery(sql).setParameter("id", id).executeUpdate(); }
    private void execute(String sql, boolean enabled, UUID id) { entityManager.createNativeQuery(sql).setParameter("enabled", enabled).setParameter("id", id).executeUpdate(); }
    private Organization requireOrganization(UUID id) { return organizations.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "조직을 찾을 수 없습니다.")); }

    public record PlatformOverview(long organizationCount, long userCount, long projectCount, long providerCount, long nodeCount, long endpointCount, long deploymentCount, long serviceCount, long apiKeyCount, long requestCount) { }
    public record OrganizationSummary(UUID id, String name, String status, int projectCount, int providerCount, int nodeCount, int memberCount) { }
    public record OrganizationCleanupPreview(UUID organizationId, String name, String status, int projectCount, int providerCount, int nodeCount, int endpointCount, int memberCount, long requestCount, String behavior) { }
    public record PlatformUserView(UUID id, String email, boolean platformAdmin, boolean enabled, int organizationCount, int issuedApiKeyCount) { }
    public record PlatformTeamView(UUID id, UUID organizationId, String organizationName, String name, String status, int projectCount) { }
    public record PlatformApiKeyView(UUID id, UUID projectId, String projectName, String organizationName, String name,
                                     String keyPrefix, String status, java.time.Instant expiresAt, java.time.Instant lastUsedAt,
                                     java.time.Instant createdAt) { }
}
