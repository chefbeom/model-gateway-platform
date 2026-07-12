package com.aiconnect.llmgateway.usage;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RequestStatus;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Usage aggregation for administrators, project owners, and API-key issuers. */
@Service
public class AdminUsageService {
    private final OrganizationRepository organizations;
    private final ProjectRepository projects;
    private final LlmRequestRepository requests;
    private final LlmServiceRepository services;
    private final ModelDeploymentRepository deployments;
    private final RuntimeEndpointRepository endpoints;
    private final InferenceNodeRepository nodes;
    private final ApiKeyRepository apiKeys;
    private final ExternalProviderRepository externalProviders;
    private final TeamAccessService access;
    private final EntityManager entityManager;

    public AdminUsageService(OrganizationRepository organizations, ProjectRepository projects,
                             LlmRequestRepository requests, LlmServiceRepository services,
                             ModelDeploymentRepository deployments, RuntimeEndpointRepository endpoints,
                             InferenceNodeRepository nodes, ApiKeyRepository apiKeys,
                             ExternalProviderRepository externalProviders,
                             TeamAccessService access, EntityManager entityManager) {
        this.organizations = organizations;
        this.projects = projects;
        this.requests = requests;
        this.services = services;
        this.deployments = deployments;
        this.endpoints = endpoints;
        this.nodes = nodes;
        this.apiKeys = apiKeys;
        this.externalProviders = externalProviders;
        this.access = access;
        this.entityManager = entityManager;
    }

    /** Legacy administrator endpoint: always returns the complete organization scope. */
    @Transactional(readOnly = true)
    public OrganizationUsageOverview overview(UUID organizationId, LocalDate from, LocalDate to) {
        requireOrganization(organizationId);
        validateRange(from, to);
        List<Project> organizationProjects = projects.findByOrganizationId(organizationId);
        List<ProjectScope> scopes = organizationProjects.stream()
                .map(project -> new ProjectScope(project.getId(), project.getName(), "ORGANIZATION_ALL", "프로젝트 전체"))
                .toList();
        List<LlmRequest> rows = requestsFor(organizationProjects.stream().map(Project::getId).toList(), from, to);
        return aggregate(organizationProjects, rows, from, to, "ORGANIZATION", "조직 전체 API 사용량", scopes);
    }

    /** Login-session endpoint. No project API-key secret is accepted or required. */
    @Transactional(readOnly = true)
    public OrganizationUsageOverview overviewForActor(UUID organizationId, AuthPrincipal actor,
                                                       LocalDate from, LocalDate to, UUID selectedProjectId) {
        requireOrganization(organizationId);
        validateRange(from, to);
        if (!access.canViewOrganization(actor, organizationId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_SCOPE_REQUIRED",
                    "The current user is not a member of this organization.");
        }

        List<Project> organizationProjects = projects.findByOrganizationId(organizationId);
        boolean organizationWide = access.isOrganizationAdmin(actor, organizationId);
        Map<UUID, ProjectScope> scopesByProject = new LinkedHashMap<>();
        for (Project project : organizationProjects) {
            if (organizationWide) {
                scopesByProject.put(project.getId(), new ProjectScope(project.getId(), project.getName(),
                        "ORGANIZATION_ALL", "관리자 · 모든 API 키"));
            } else if (access.canViewProject(actor, project.getId())) {
                boolean managesProject = access.canManageProject(actor, project.getId());
                scopesByProject.put(project.getId(), new ProjectScope(project.getId(), project.getName(),
                        managesProject ? "PROJECT_ALL" : "OWN_KEYS",
                        managesProject ? "프로젝트 소유 · 모든 API 키" : "내가 발급한 API 키"));
            }
        }

        if (selectedProjectId != null && !scopesByProject.containsKey(selectedProjectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_USAGE_ACCESS_DENIED",
                    "The current role cannot view usage for this project.");
        }

        List<LlmRequest> organizationRows = requestsFor(
                organizationProjects.stream().map(Project::getId).toList(), from, to);
        List<LlmRequest> visibleRows = organizationRows.stream()
                .filter(row -> selectedProjectId == null || selectedProjectId.equals(row.getProjectId()))
                .filter(row -> {
                    ProjectScope projectScope = scopesByProject.get(row.getProjectId());
                    if (projectScope == null) return false;
                    if ("ORGANIZATION_ALL".equals(projectScope.access()) || "PROJECT_ALL".equals(projectScope.access())) return true;
                    return actor.userId().equals(row.getApiKeyIssuerUserId());
                })
                .toList();

        String scope;
        String scopeLabel;
        if (organizationWide) {
            scope = "ORGANIZATION";
            scopeLabel = selectedProjectId == null ? "조직 전체 API 사용량"
                    : scopesByProject.get(selectedProjectId).name() + " · 모든 API 키";
        } else if (scopesByProject.values().stream().anyMatch(item -> "PROJECT_ALL".equals(item.access()))) {
            scope = "PROJECT_OWNER";
            scopeLabel = selectedProjectId == null ? "소유 프로젝트 전체 + 직접 발급한 API 키"
                    : projectScopeLabel(scopesByProject.get(selectedProjectId));
        } else {
            scope = "KEY_ISSUER";
            scopeLabel = selectedProjectId == null ? "내가 발급한 API 키 사용량"
                    : scopesByProject.get(selectedProjectId).name() + " · 내가 발급한 API 키";
        }
        return aggregate(organizationProjects, visibleRows, from, to, scope, scopeLabel,
                new ArrayList<>(scopesByProject.values()));
    }

    private String projectScopeLabel(ProjectScope scope) {
        return scope.name() + ("PROJECT_ALL".equals(scope.access())
                ? " · 프로젝트의 모든 API 키" : " · 내가 발급한 API 키");
    }

    private OrganizationUsageOverview aggregate(List<Project> organizationProjects, List<LlmRequest> rows,
                                                LocalDate from, LocalDate to, String scope, String scopeLabel,
                                                List<ProjectScope> availableProjects) {
        Map<UUID, Project> projectsById = indexById(organizationProjects, Project::getId);
        Map<UUID, LlmService> servicesById = indexById(services.findAll(), LlmService::getId);
        Map<UUID, ModelDeployment> deploymentsById = indexById(deployments.findAll(), ModelDeployment::getId);
        Map<UUID, RuntimeEndpoint> endpointsById = indexById(endpoints.findAll(), RuntimeEndpoint::getId);
        Map<UUID, InferenceNode> nodesById = indexById(nodes.findAll(), InferenceNode::getId);
        Map<UUID, ApiKey> apiKeysById = indexById(apiKeys.findAll(), ApiKey::getId);
        Map<UUID, ExternalProvider> externalProvidersById = indexById(externalProviders.findAll(), ExternalProvider::getId);

        Aggregate total = new Aggregate("전체", scopeLabel);
        Map<UUID, Aggregate> projectGroups = new HashMap<>();
        Map<UUID, Aggregate> serviceGroups = new HashMap<>();
        Map<String, Aggregate> infrastructureGroups = new HashMap<>();
        Map<String, Aggregate> apiKeyGroups = new HashMap<>();
        List<RecentRequest> recent = new ArrayList<>();

        for (LlmRequest row : rows) {
            total.add(row);
            Project project = projectsById.get(row.getProjectId());
            projectGroups.computeIfAbsent(row.getProjectId(), id -> new Aggregate(
                    project == null ? "삭제된 프로젝트" : project.getName(), "PROJECT")).add(row);

            LlmService service = servicesById.get(row.getServiceId());
            serviceGroups.computeIfAbsent(row.getServiceId(), id -> new Aggregate(
                    service == null ? "삭제된 논리 서비스" : service.getServiceKey(),
                    service == null ? row.getServiceId().toString() : service.getDisplayName())).add(row);

            InfrastructureLabel infrastructure = infrastructureLabel(row, deploymentsById, endpointsById, nodesById, externalProvidersById);
            infrastructureGroups.computeIfAbsent(infrastructure.key(), id ->
                    new Aggregate(infrastructure.title(), infrastructure.detail())).add(row);

            String apiKeyKey = row.getApiKeyId() == null ? "deleted:" + String.valueOf(row.getApiKeyIssuerUserId())
                    : row.getApiKeyId().toString();
            ApiKey apiKey = row.getApiKeyId() == null ? null : apiKeysById.get(row.getApiKeyId());
            apiKeyGroups.computeIfAbsent(apiKeyKey, id -> new Aggregate(
                    apiKey == null ? "삭제된 API 키" : apiKey.getName(),
                    apiKey == null ? "키 기록 삭제됨 · 발급자 기준 이력 보존" : apiKey.getKeyPrefix())).add(row);

            if (recent.size() < 100) {
                recent.add(new RecentRequest(row.getRequestId(),
                        project == null ? "삭제된 프로젝트" : project.getName(),
                        service == null ? "삭제된 논리 서비스" : service.getServiceKey(),
                        infrastructure.title(), apiKey == null ? "삭제된 API 키" : apiKey.getKeyPrefix(),
                        row.getStatus().name(), tokens(row.getInputTokens()), tokens(row.getOutputTokens()),
                        cost(row.getEstimatedCost()), row.getLatencyMs(), row.getFailoverCount(),
                        row.getErrorCode(), row.getStartedAt()));
            }
        }

        return new OrganizationUsageOverview(total.view(), views(projectGroups.values()),
                views(serviceGroups.values()), views(infrastructureGroups.values()), views(apiKeyGroups.values()),
                recent, from, to, scope, scopeLabel, availableProjects);
    }

    private List<LlmRequest> requestsFor(Collection<UUID> projectIds, LocalDate from, LocalDate to) {
        if (projectIds.isEmpty()) return List.of();
        StringBuilder jpql = new StringBuilder("select r from LlmRequest r where r.projectId in :projectIds");
        Instant start = from == null ? null : from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endExclusive = to == null ? null : to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        if (start != null) jpql.append(" and r.startedAt >= :start");
        if (endExclusive != null) jpql.append(" and r.startedAt < :endExclusive");
        jpql.append(" order by r.startedAt desc");
        var query = entityManager.createQuery(jpql.toString(), LlmRequest.class)
                .setParameter("projectIds", projectIds);
        if (start != null) query.setParameter("start", start);
        if (endExclusive != null) query.setParameter("endExclusive", endExclusive);
        return query.getResultList();
    }

    private InfrastructureLabel infrastructureLabel(LlmRequest row,
                                                     Map<UUID, ModelDeployment> deploymentsById,
                                                     Map<UUID, RuntimeEndpoint> endpointsById,
                                                     Map<UUID, InferenceNode> nodesById,
                                                     Map<UUID, ExternalProvider> externalProvidersById) {
        if (row.getFinalDeploymentId() == null) {
            return new InfrastructureLabel("unresolved", "처리 인프라 미확정", "라우팅 또는 Runtime 응답 전 실패");
        }
        ModelDeployment deployment = deploymentsById.get(row.getFinalDeploymentId());
        if (deployment == null) {
            return new InfrastructureLabel("deployment:" + row.getFinalDeploymentId(),
                    "삭제된 배포", row.getFinalDeploymentId().toString());
        }
        if (deployment.isExternal()) {
            ExternalProvider provider = externalProvidersById.get(deployment.getExternalProviderId());
            String providerName = provider == null ? "삭제된 외부 Provider" : provider.getDisplayName();
            return new InfrastructureLabel("external:" + deployment.getId(),
                    "CLOUD · " + providerName + " · " + deployment.getDisplayName(),
                    deployment.getProviderModelId() + " · " + String.valueOf(row.getRoutingReason()));
        }
        RuntimeEndpoint endpoint = endpointsById.get(deployment.getRuntimeEndpointId());
        InferenceNode node = endpoint == null ? null : nodesById.get(endpoint.getNodeId());
        String nodeName = node == null ? "삭제된 노드" : node.getName();
        String endpointName = endpoint == null ? "Endpoint 정보 없음" : endpoint.getBaseUrl();
        return new InfrastructureLabel("deployment:" + deployment.getId(),
                nodeName + " · " + deployment.getDisplayName(),
                endpointName + " · " + deployment.getProviderModelId());
    }

    private void requireOrganization(UUID organizationId) {
        if (!organizations.existsById(organizationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "조직을 찾을 수 없습니다.");
        }
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USAGE_RANGE",
                    "조회 종료일은 시작일보다 빠를 수 없습니다.");
        }
    }

    private <T> Map<UUID, T> indexById(List<T> values, java.util.function.Function<T, UUID> id) {
        Map<UUID, T> indexed = new HashMap<>();
        for (T value : values) indexed.put(id.apply(value), value);
        return indexed;
    }

    private List<UsageMetric> views(Collection<Aggregate> values) {
        return values.stream().map(Aggregate::view)
                .sorted(Comparator.comparingLong(UsageMetric::requestCount).reversed()
                        .thenComparing(UsageMetric::label))
                .toList();
    }

    private long tokens(Integer value) { return value == null ? 0L : value.longValue(); }
    private BigDecimal cost(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private static final class Aggregate {
        private final String label;
        private final String detail;
        private long requestCount;
        private long succeeded;
        private long failed;
        private long inputTokens;
        private long outputTokens;
        private long failovers;
        private long latencyTotal;
        private long latencyCount;
        private BigDecimal estimatedCost = BigDecimal.ZERO;

        private Aggregate(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }

        private void add(LlmRequest request) {
            requestCount++;
            if (request.getStatus() == RequestStatus.SUCCEEDED) succeeded++;
            if (request.getStatus() == RequestStatus.FAILED) failed++;
            inputTokens += request.getInputTokens() == null ? 0 : request.getInputTokens();
            outputTokens += request.getOutputTokens() == null ? 0 : request.getOutputTokens();
            failovers += request.getFailoverCount();
            if (request.getLatencyMs() != null) {
                latencyTotal += request.getLatencyMs();
                latencyCount++;
            }
            if (request.getEstimatedCost() != null) {
                estimatedCost = estimatedCost.add(request.getEstimatedCost());
            }
        }

        private UsageMetric view() {
            return new UsageMetric(label, detail, requestCount, succeeded, failed, inputTokens, outputTokens,
                    estimatedCost, failovers,
                    latencyCount == 0 ? 0 : Math.round((double) latencyTotal / latencyCount));
        }
    }

    private record InfrastructureLabel(String key, String title, String detail) { }

    public record OrganizationUsageOverview(UsageMetric total, List<UsageMetric> byProject,
                                            List<UsageMetric> byService, List<UsageMetric> byInfrastructure,
                                            List<UsageMetric> byApiKey, List<RecentRequest> recentRequests,
                                            LocalDate periodFrom, LocalDate periodTo,
                                            String scope, String scopeLabel,
                                            List<ProjectScope> availableProjects) { }

    public record ProjectScope(UUID id, String name, String access, String accessLabel) { }

    public record UsageMetric(String label, String detail, long requestCount, long succeeded, long failed,
                              long inputTokens, long outputTokens, BigDecimal estimatedCost,
                              long failovers, long averageLatencyMs) { }

    public record RecentRequest(String requestId, String projectName, String serviceKey,
                                String infrastructure, String apiKeyLabel, String status,
                                long inputTokens, long outputTokens, BigDecimal estimatedCost,
                                Long latencyMs, int failoverCount, String errorCode, Instant startedAt) { }
}

