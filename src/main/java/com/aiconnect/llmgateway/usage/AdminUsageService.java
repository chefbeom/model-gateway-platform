package com.aiconnect.llmgateway.usage;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.PlaygroundRequest;
import com.aiconnect.llmgateway.domain.RequestStatus;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.monitoring.RequestAttemptQueryRepository;
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
import java.math.RoundingMode;
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
import java.util.TreeMap;

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
    private final RequestDetailService requestDetails;
    private final RequestAttemptQueryRepository attemptQueries;

    public AdminUsageService(OrganizationRepository organizations, ProjectRepository projects,
                             LlmRequestRepository requests, LlmServiceRepository services,
                             ModelDeploymentRepository deployments, RuntimeEndpointRepository endpoints,
                             InferenceNodeRepository nodes, ApiKeyRepository apiKeys,
                             ExternalProviderRepository externalProviders,
                             TeamAccessService access, EntityManager entityManager,
                             RequestDetailService requestDetails,
                             RequestAttemptQueryRepository attemptQueries) {
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
        this.requestDetails = requestDetails;
        this.attemptQueries = attemptQueries;
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
        return aggregate(organizationId, organizationProjects, rows, from, to,
                "ORGANIZATION", "조직 전체 API 사용량", scopes, true);
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
        return aggregate(organizationId, organizationProjects, visibleRows, from, to, scope, scopeLabel,
                new ArrayList<>(scopesByProject.values()), organizationWide);
    }

    @Transactional(readOnly = true)
    public RequestDetailService.RequestDetail requestDetailForActor(UUID organizationId,
                                                                      AuthPrincipal actor,
                                                                      String requestId) {
        requireOrganization(organizationId);
        if (!access.canViewOrganization(actor, organizationId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_SCOPE_REQUIRED",
                    "The current user is not a member of this organization.");
        }

        LlmRequest request = requests.findByRequestId(requestId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND",
                        "The request does not exist."));
        Project project = projects.findById(request.getProjectId()).orElse(null);
        if (project == null || !organizationId.equals(project.getOrganizationId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND",
                    "The request does not exist in this organization.");
        }

        boolean organizationAdmin = access.isOrganizationAdmin(actor, organizationId);
        boolean projectManager = access.canManageProject(actor, project.getId());
        boolean ownKeyRequest = actor.userId().equals(request.getApiKeyIssuerUserId());
        if (!organizationAdmin && !projectManager && !ownKeyRequest) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REQUEST_DETAIL_ACCESS_DENIED",
                    "The current role cannot view this request.");
        }
        return requestDetails.view(request);
    }
    private String projectScopeLabel(ProjectScope scope) {
        return scope.name() + ("PROJECT_ALL".equals(scope.access())
                ? " · 프로젝트의 모든 API 키" : " · 내가 발급한 API 키");
    }

    private OrganizationUsageOverview aggregate(UUID organizationId, List<Project> organizationProjects, List<LlmRequest> rows,
                                                LocalDate from, LocalDate to, String scope, String scopeLabel,
                                                List<ProjectScope> availableProjects, boolean includePlayground) {
        Map<UUID, Project> projectsById = indexById(organizationProjects, Project::getId);
        Map<UUID, LlmService> servicesById = indexById(services.findAll(), LlmService::getId);
        Map<UUID, ModelDeployment> deploymentsById = indexById(deployments.findAll(), ModelDeployment::getId);
        Map<UUID, RuntimeEndpoint> endpointsById = indexById(endpoints.findAll(), RuntimeEndpoint::getId);
        Map<UUID, InferenceNode> nodesById = indexById(nodes.findAll(), InferenceNode::getId);
        Map<UUID, ApiKey> apiKeysById = indexById(apiKeys.findAll(), ApiKey::getId);
        Map<UUID, ExternalProvider> externalProvidersById = indexById(externalProviders.findAll(), ExternalProvider::getId);
        RuntimeAnalytics runtimeAnalytics = runtimeAnalytics(organizationId, rows, deploymentsById,
                endpointsById, nodesById, externalProvidersById, from, to, includePlayground);

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
                        cost(row.getEstimatedCost()), row.getCostCurrency() == null ? Currency.KRW.name() : row.getCostCurrency().name(),                         row.getLatencyMs(), row.getFailoverCount(),
                        row.getErrorCode(), row.getStartedAt(), row.getReasoningEffort(),
                        row.getRequestedServiceTier(), row.getActualServiceTier()));
            }
        }

        return new OrganizationUsageOverview(total.view(), views(projectGroups.values()),
                views(serviceGroups.values()), views(infrastructureGroups.values()), views(apiKeyGroups.values()),
                recent, from, to, scope, scopeLabel, availableProjects, runtimeAnalytics);
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

    /**
     * Builds statistics from durable metadata only. Production logical requests and
     * direct Playground probes stay separate because Playground does not record billing.
     */
    private RuntimeAnalytics runtimeAnalytics(UUID organizationId, List<LlmRequest> rows,
                                               Map<UUID, ModelDeployment> deploymentsById,
                                               Map<UUID, RuntimeEndpoint> endpointsById,
                                               Map<UUID, InferenceNode> nodesById,
                                               Map<UUID, ExternalProvider> externalProvidersById,
                                               LocalDate from, LocalDate to, boolean includePlayground) {
        Map<String, RuntimeBucket> servers = new LinkedHashMap<>();
        Map<String, ModelBucket> models = new LinkedHashMap<>();
        Map<String, Long> requestTypes = new HashMap<>();
        Map<String, Long> capabilities = new HashMap<>();
        Map<String, Long> streamModes = new HashMap<>();
        Map<String, Long> reasoningModes = new HashMap<>();
        Map<String, Long> serviceTiers = new HashMap<>();
        Map<String, Long> finalErrors = new HashMap<>();
        Map<String, AttemptErrorBucket> attemptErrors = new HashMap<>();
        Map<String, DailyBucket> daily = new TreeMap<>();
        boolean monthly = periodIsLong(rows, from, to);

        // Administrators should also see configured servers with zero requests. Do not
        // expose the organization inventory to project-scoped developer usage views.
        if (includePlayground) {
            for (RuntimeEndpoint endpoint : endpointsById.values()) {
                InferenceNode node = nodesById.get(endpoint.getNodeId());
                if (node == null || !organizationId.equals(node.getOrganizationId())) continue;
                RuntimeIdentity identity = new RuntimeIdentity("runtime:" + endpoint.getId(), "RUNTIME",
                        endpoint.getDisplayName(), node.getName() + " · " + endpoint.getRuntimeType().name());
                servers.putIfAbsent(identity.key(), new RuntimeBucket(identity));
            }
            for (ExternalProvider provider : externalProvidersById.values()) {
                if (!organizationId.equals(provider.getOrganizationId())) continue;
                String providerType = provider.getProviderType() == null ? "EXTERNAL" : provider.getProviderType().name();
                RuntimeIdentity identity = new RuntimeIdentity("provider:" + provider.getId(),
                        "EXTERNAL_PROVIDER", provider.getDisplayName(), providerType);
                servers.putIfAbsent(identity.key(), new RuntimeBucket(identity));
            }
        }

        for (LlmRequest row : rows) {
            String requestType = normalizeRequestType(row.getRequestType());
            increment(requestTypes, requestType);
            if ("UNKNOWN".equals(requestType)) increment(capabilities, "UNKNOWN");
            else if ("TEXT_CHAT".equals(requestType)) increment(capabilities, "TEXT_CHAT");
            else {
                for (String capability : requestType.split("\\+")) {
                    increment(capabilities, capability);
                }
            }
            increment(reasoningModes, normalized(row.getReasoningEffort(), "UNSPECIFIED"));
            increment(streamModes, row.isStream() ? "STREAMING" : "NON_STREAMING");
            String requestedTier = normalized(row.getRequestedServiceTier(), "DEFAULT");
            String actualTier = normalized(row.getActualServiceTier(), "UNKNOWN");
            increment(serviceTiers, requestedTier + " → " + actualTier);
            if (row.getErrorCode() != null && !row.getErrorCode().isBlank()) increment(finalErrors, row.getErrorCode());

            ModelDeployment deployment = row.getFinalDeploymentId() == null ? null : deploymentsById.get(row.getFinalDeploymentId());
            RuntimeIdentity server = runtimeIdentity(deployment, endpointsById, nodesById, externalProvidersById);
            RuntimeBucket serverBucket = servers.computeIfAbsent(server.key(), key -> new RuntimeBucket(server));
            serverBucket.requests.add(row);
            if (deployment != null) {
                ModelIdentity model = modelIdentity(deployment, server);
                ModelBucket modelBucket = models.computeIfAbsent(model.key(), key -> new ModelBucket(model));
                modelBucket.requests.add(row);
            }

            if (row.getStartedAt() != null) {
                LocalDate date = row.getStartedAt().atZone(ZoneOffset.UTC).toLocalDate();
                String bucketKey = monthly ? date.getYear() + String.format("-%02d", date.getMonthValue()) : date.toString();
                daily.computeIfAbsent(bucketKey, ignored -> new DailyBucket()).add(row);
            }
        }

        List<UUID> requestIds = rows.stream().map(LlmRequest::getId).filter(java.util.Objects::nonNull).toList();
        if (!requestIds.isEmpty()) {
            for (int offset = 0; offset < requestIds.size(); offset += 500) {
                List<UUID> batch = requestIds.subList(offset, Math.min(offset + 500, requestIds.size()));
                for (RequestAttemptQueryRepository.UsageAttemptProjection attempt : attemptQueries.findAttemptsForRequests(batch)) {
                    ModelDeployment deployment = deploymentsById.get(attempt.getDeploymentId());
                    RuntimeIdentity server = runtimeIdentity(deployment, endpointsById, nodesById, externalProvidersById);
                    RuntimeBucket serverBucket = servers.computeIfAbsent(server.key(), key -> new RuntimeBucket(server));
                    serverBucket.attempts.add(attempt);
                    if (deployment != null) {
                        ModelIdentity model = modelIdentity(deployment, server);
                        ModelBucket modelBucket = models.computeIfAbsent(model.key(), key -> new ModelBucket(model));
                        modelBucket.attempts.add(attempt);
                        if ("FAILED".equalsIgnoreCase(attempt.getStatus())) {
                            String errorType = normalized(attempt.getErrorType(), "UNKNOWN");
                            String errorKey = model.key() + "|" + errorType;
                            AttemptErrorBucket failure = attemptErrors.computeIfAbsent(errorKey,
                                    ignored -> new AttemptErrorBucket(server.name(), model.name(), errorType));
                            failure.count++;
                        }
                    }
                }
            }
        }

        List<RuntimeUsage> serverViews = servers.values().stream().map(RuntimeBucket::view)
                .sorted(Comparator.comparingLong((RuntimeUsage item) -> item.attempts().total()).reversed()
                        .thenComparing(item -> item.requests().requestCount(), Comparator.reverseOrder())
                        .thenComparing(RuntimeUsage::serverName)).toList();
        List<ModelUsage> modelViews = models.values().stream().map(ModelBucket::view)
                .sorted(Comparator.comparingLong((ModelUsage item) -> item.attempts().total()).reversed()
                        .thenComparing(item -> item.requests().succeeded(), Comparator.reverseOrder())
                        .thenComparing(ModelUsage::modelName)).toList();
        long requestCount = rows.size();
        List<CapabilityUsage> capabilityViews = capabilityViews(capabilities, requestCount);
        List<Breakdown> requestTypeViews = breakdownViews(requestTypes, requestCount);
        List<Breakdown> reasoningViews = breakdownViews(reasoningModes, requestCount);
        List<Breakdown> tierViews = breakdownViews(serviceTiers, requestCount);
        List<Breakdown> errorViews = breakdownViews(finalErrors, finalErrors.values().stream().mapToLong(Long::longValue).sum());
        List<AttemptFailure> attemptFailureViews = attemptErrors.values().stream()
                .map(item -> new AttemptFailure(item.serverName, item.modelName, item.errorType, item.count))
                .sorted(Comparator.comparingLong(AttemptFailure::count).reversed()
                        .thenComparing(AttemptFailure::errorType)).limit(30).toList();
        List<TimeSeriesPoint> timeSeries = daily.entrySet().stream()
                .map(entry -> entry.getValue().view(entry.getKey()))
                .toList();
        PlaygroundAnalytics playground = includePlayground
                ? playgroundAnalytics(organizationId, from, to, deploymentsById, endpointsById, nodesById,
                externalProvidersById, monthly)
                : null;

        return new RuntimeAnalytics(serverViews, modelViews, requestTypeViews, capabilityViews,
                breakdownViews(streamModes, requestCount),
                reasoningViews, tierViews, errorViews, attemptFailureViews, timeSeries,
                monthly ? "MONTH" : "DAY", playground);
    }

    private PlaygroundAnalytics playgroundAnalytics(UUID organizationId, LocalDate from, LocalDate to,
                                                    Map<UUID, ModelDeployment> deploymentsById,
                                                    Map<UUID, RuntimeEndpoint> endpointsById,
                                                    Map<UUID, InferenceNode> nodesById,
                                                    Map<UUID, ExternalProvider> externalProvidersById,
                                                    boolean monthly) {
        List<PlaygroundRequest> traces = playgroundRequestsFor(organizationId, from, to);
        TraceBucket total = new TraceBucket("전체", "직접 모델 테스트 · 청구 비용 미산정");
        Map<String, TraceBucket> serverGroups = new LinkedHashMap<>();
        Map<String, TraceBucket> modelGroups = new LinkedHashMap<>();
        Map<String, Long> requestTypes = new HashMap<>();
        Map<String, Long> streamModes = new HashMap<>();
        Map<String, Long> errors = new HashMap<>();
        Map<String, DailyBucket> daily = new TreeMap<>();
        for (PlaygroundRequest trace : traces) {
            String type = normalizeRequestType(trace.getRequestType());
            increment(requestTypes, type);
            increment(streamModes, trace.isStream() ? "STREAMING" : "NON_STREAMING");
            if (trace.getErrorCode() != null && !trace.getErrorCode().isBlank()) increment(errors, trace.getErrorCode());
            ModelDeployment deployment = trace.getDeploymentId() == null ? null : deploymentsById.get(trace.getDeploymentId());
            RuntimeIdentity server = runtimeIdentity(deployment, endpointsById, nodesById, externalProvidersById);
            TraceBucket serverBucket = serverGroups.computeIfAbsent(server.key(), key -> new TraceBucket(server.name(), server.detail()));
            String modelKey = deployment == null ? "unknown:" + trace.getModelId() : deployment.getId().toString();
            TraceBucket modelBucket = modelGroups.computeIfAbsent(modelKey, key -> new TraceBucket(
                    deployment == null ? trace.getModelId() : deployment.getDisplayName(),
                    server.name() + " · " + trace.getModelId()));
            total.add(trace);
            serverBucket.add(trace);
            modelBucket.add(trace);
            if (trace.getStartedAt() != null) {
                LocalDate date = trace.getStartedAt().atZone(ZoneOffset.UTC).toLocalDate();
                String bucketKey = monthly ? date.getYear() + String.format("-%02d", date.getMonthValue()) : date.toString();
                daily.computeIfAbsent(bucketKey, ignored -> new DailyBucket()).add(trace);
            }
        }
        List<TraceUsage> byServer = serverGroups.values().stream().map(TraceBucket::view)
                .sorted(Comparator.comparingLong(TraceUsage::requestCount).reversed().thenComparing(TraceUsage::label)).toList();
        List<TraceUsage> byModel = modelGroups.values().stream().map(TraceBucket::view)
                .sorted(Comparator.comparingLong(TraceUsage::requestCount).reversed().thenComparing(TraceUsage::label)).toList();
        long count = traces.size();
        return new PlaygroundAnalytics(total.view(), byServer, byModel,
                breakdownViews(requestTypes, count), breakdownViews(streamModes, count), breakdownViews(errors,
                errors.values().stream().mapToLong(Long::longValue).sum()),
                daily.entrySet().stream().map(entry -> entry.getValue().view(entry.getKey())).toList());
    }

    private List<PlaygroundRequest> playgroundRequestsFor(UUID organizationId, LocalDate from, LocalDate to) {
        StringBuilder jpql = new StringBuilder("select p from PlaygroundRequest p where p.organizationId = :organizationId");
        Instant start = from == null ? null : from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant endExclusive = to == null ? null : to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        if (start != null) jpql.append(" and p.startedAt >= :start");
        if (endExclusive != null) jpql.append(" and p.startedAt < :endExclusive");
        jpql.append(" order by p.startedAt desc");
        var query = entityManager.createQuery(jpql.toString(), PlaygroundRequest.class)
                .setParameter("organizationId", organizationId);
        if (start != null) query.setParameter("start", start);
        if (endExclusive != null) query.setParameter("endExclusive", endExclusive);
        return query.getResultList();
    }

    private RuntimeIdentity runtimeIdentity(ModelDeployment deployment,
                                           Map<UUID, RuntimeEndpoint> endpointsById,
                                           Map<UUID, InferenceNode> nodesById,
                                           Map<UUID, ExternalProvider> externalProvidersById) {
        if (deployment == null) return new RuntimeIdentity("unresolved", "UNRESOLVED", "대상 미확정",
                "최종 Runtime/Provider 미확정 · 개별 시도 오류는 아래 모델별 분석에 포함");
        if (deployment.isExternal()) {
            ExternalProvider provider = externalProvidersById.get(deployment.getExternalProviderId());
            String name = provider == null ? "삭제된 외부 Provider" : provider.getDisplayName();
            String providerType = provider == null || provider.getProviderType() == null ? "EXTERNAL" : provider.getProviderType().name();
            return new RuntimeIdentity("provider:" + deployment.getExternalProviderId(), "EXTERNAL_PROVIDER", name, providerType);
        }
        RuntimeEndpoint endpoint = endpointsById.get(deployment.getRuntimeEndpointId());
        if (endpoint == null) return new RuntimeIdentity("runtime:" + deployment.getRuntimeEndpointId(),
                "RUNTIME", "보관/삭제된 Runtime", "Endpoint 정보 미확정");
        InferenceNode node = nodesById.get(endpoint.getNodeId());
        String nodeName = node == null ? "노드 정보 없음" : node.getName();
        String runtimeType = endpoint.getRuntimeType() == null ? "RUNTIME" : endpoint.getRuntimeType().name();
        return new RuntimeIdentity("runtime:" + endpoint.getId(), "RUNTIME", endpoint.getDisplayName(),
                nodeName + " · " + runtimeType);
    }

    private ModelIdentity modelIdentity(ModelDeployment deployment, RuntimeIdentity server) {
        return new ModelIdentity(deployment.getId().toString(), deployment.getDisplayName(),
                server.name() + " · " + deployment.getProviderModelId());
    }

    private boolean periodIsLong(List<LlmRequest> rows, LocalDate from, LocalDate to) {
        LocalDate start = from;
        LocalDate end = to;
        if (start == null) start = rows.stream().map(LlmRequest::getStartedAt).filter(java.util.Objects::nonNull)
                .map(value -> value.atZone(ZoneOffset.UTC).toLocalDate()).min(LocalDate::compareTo).orElse(null);
        if (end == null) end = rows.stream().map(LlmRequest::getStartedAt).filter(java.util.Objects::nonNull)
                .map(value -> value.atZone(ZoneOffset.UTC).toLocalDate()).max(LocalDate::compareTo).orElse(null);
        return start == null || end == null || java.time.temporal.ChronoUnit.DAYS.between(start, end) > 92;
    }

    private List<Breakdown> breakdownViews(Map<String, Long> counts, long denominator) {
        return counts.entrySet().stream().map(entry -> new Breakdown(entry.getKey(), entry.getValue(),
                        percentage(entry.getValue(), denominator)))
                .sorted(Comparator.comparingLong(Breakdown::requests).reversed().thenComparing(Breakdown::label))
                .limit(30).toList();
    }

    private List<CapabilityUsage> capabilityViews(Map<String, Long> counts, long denominator) {
        return breakdownViews(counts, denominator).stream()
                .map(item -> new CapabilityUsage(item.label(), item.requests(), item.percentOfRequests())).toList();
    }

    private double percentage(long count, long denominator) {
        return denominator == 0 ? 0 : BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP).doubleValue();
    }

    private void increment(Map<String, Long> values, String key) {
        values.merge(key, 1L, Long::sum);
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String normalizeRequestType(String value) {
        String type = normalized(value, "UNKNOWN");
        return "CHAT_COMPLETION".equals(type) ? "TEXT_CHAT" : type;
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
        private final Map<String, BigDecimal> estimatedCostByCurrency = new LinkedHashMap<>();

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
                String currency = request.getCostCurrency() == null ? Currency.KRW.name() : request.getCostCurrency().name();
                estimatedCostByCurrency.merge(currency, request.getEstimatedCost(), BigDecimal::add);
            }
        }

        private UsageMetric view() {
            return new UsageMetric(label, detail, requestCount, succeeded, failed, inputTokens, outputTokens,
                    estimatedCost, new LinkedHashMap<>(estimatedCostByCurrency), failovers,
                    latencyCount == 0 ? 0 : Math.round((double) latencyTotal / latencyCount));
        }
    }

    private static final class RuntimeBucket {
        private final RuntimeIdentity identity;
        private final RequestAccumulator requests = new RequestAccumulator();
        private final AttemptAccumulator attempts = new AttemptAccumulator();
        private RuntimeBucket(RuntimeIdentity identity) { this.identity = identity; }
        private RuntimeUsage view() { return new RuntimeUsage(identity.key(), identity.kind(), identity.name(), identity.detail(), requests.view(), attempts.view()); }
    }

    private static final class ModelBucket {
        private final ModelIdentity identity;
        private final RequestAccumulator requests = new RequestAccumulator();
        private final AttemptAccumulator attempts = new AttemptAccumulator();
        private ModelBucket(ModelIdentity identity) { this.identity = identity; }
        private ModelUsage view() { return new ModelUsage(identity.key(), identity.name(), identity.detail(), requests.view(), attempts.view()); }
    }

    private static final class RequestAccumulator {
        private long requestCount;
        private long succeeded;
        private long failed;
        private long inProgress;
        private long failoverRequests;
        private long failoverCount;
        private long totalInputTokens;
        private long totalOutputTokens;
        private long totalReasoningTokens;
        private long totalCachedInputTokens;
        private long unknownCostRequests;
        private final NumericAccumulator latency = new NumericAccumulator();
        private final NumericAccumulator input = new NumericAccumulator();
        private final NumericAccumulator output = new NumericAccumulator();
        private final NumericAccumulator total = new NumericAccumulator();
        private final Map<String, BigDecimal> costByCurrency = new LinkedHashMap<>();

        private void add(LlmRequest request) {
            requestCount++;
            if (request.getStatus() == RequestStatus.SUCCEEDED) succeeded++;
            else if (request.getStatus() == RequestStatus.FAILED) failed++;
            else inProgress++;
            if (request.getFailoverCount() > 0) failoverRequests++;
            failoverCount += request.getFailoverCount();
            if (request.getInputTokens() != null) {
                totalInputTokens += request.getInputTokens();
                input.add(request.getInputTokens().longValue());
            }
            if (request.getOutputTokens() != null) {
                totalOutputTokens += request.getOutputTokens();
                output.add(request.getOutputTokens().longValue());
            }
            if (request.getInputTokens() != null && request.getOutputTokens() != null) {
                total.add((long) request.getInputTokens() + request.getOutputTokens());
            }
            if (request.getReasoningTokens() != null) totalReasoningTokens += request.getReasoningTokens();
            if (request.getCachedInputTokens() != null) totalCachedInputTokens += request.getCachedInputTokens();
            latency.add(request.getLatencyMs());
            if (request.getEstimatedCost() != null) {
                String currency = request.getCostCurrency() == null ? Currency.KRW.name() : request.getCostCurrency().name();
                costByCurrency.merge(currency, request.getEstimatedCost(), BigDecimal::add);
            } else if (request.getStatus() == RequestStatus.SUCCEEDED) {
                unknownCostRequests++;
            }
        }

        private RequestMetrics view() {
            long completed = succeeded + failed;
            BigDecimal successRate = completed == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(succeeded)
                    .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(completed), 1, RoundingMode.HALF_UP);
            return new RequestMetrics(requestCount, completed, succeeded, failed, inProgress, successRate,
                    failoverRequests, failoverCount, totalInputTokens, totalOutputTokens,
                    totalReasoningTokens, totalCachedInputTokens, unknownCostRequests,
                    latency.view(), input.view(), output.view(), total.view(), new LinkedHashMap<>(costByCurrency));
        }
    }

    private static final class AttemptAccumulator {
        private long total;
        private long succeeded;
        private long failed;
        private long inProgress;
        private final NumericAccumulator latency = new NumericAccumulator();
        private void add(RequestAttemptQueryRepository.UsageAttemptProjection attempt) {
            total++;
            if ("SUCCEEDED".equalsIgnoreCase(attempt.getStatus())) succeeded++;
            else if ("FAILED".equalsIgnoreCase(attempt.getStatus())) failed++;
            else inProgress++;
            latency.add(attempt.getLatencyMs());
        }
        private AttemptMetrics view() {
            long completed = succeeded + failed;
            BigDecimal rate = completed == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(succeeded)
                    .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(completed), 1, RoundingMode.HALF_UP);
            return new AttemptMetrics(total, succeeded, failed, inProgress, rate, latency.view());
        }
    }

    private static final class NumericAccumulator {
        private long count;
        private long sum;
        private Long min;
        private Long max;
        private void add(Number value) { if (value != null) add(value.longValue()); }
        private void add(Long value) {
            if (value == null) return;
            count++;
            sum += value;
            min = min == null ? value : Math.min(min, value);
            max = max == null ? value : Math.max(max, value);
        }
        private NumericSummary view() {
            BigDecimal average = count == 0 ? null : BigDecimal.valueOf(sum)
                    .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            return new NumericSummary(count, average, min, max);
        }
    }


    private static final class DailyBucket {
        private long requests;
        private long succeeded;
        private long failed;
        private long tokens;
        private void add(LlmRequest request) {
            requests++;
            if (request.getStatus() == RequestStatus.SUCCEEDED) succeeded++;
            if (request.getStatus() == RequestStatus.FAILED) failed++;
            if (request.getInputTokens() != null) tokens += request.getInputTokens();
            if (request.getOutputTokens() != null) tokens += request.getOutputTokens();
        }
        private void add(PlaygroundRequest request) {
            requests++;
            if ("SUCCEEDED".equalsIgnoreCase(request.getStatus())) succeeded++;
            if ("FAILED".equalsIgnoreCase(request.getStatus())) failed++;
            if (request.getInputTokens() != null) tokens += request.getInputTokens();
            if (request.getOutputTokens() != null) tokens += request.getOutputTokens();
        }
        private TimeSeriesPoint view(String period) { return new TimeSeriesPoint(period, requests, succeeded, failed, tokens); }
    }

    private static final class TraceBucket {
        private final String label;
        private final String detail;
        private long requests;
        private long succeeded;
        private long failed;
        private long unknownTokenRequests;
        private long streamingRequests;
        private final NumericAccumulator latency = new NumericAccumulator();
        private final NumericAccumulator input = new NumericAccumulator();
        private final NumericAccumulator output = new NumericAccumulator();
        private final NumericAccumulator total = new NumericAccumulator();
        private TraceBucket(String label, String detail) { this.label = label; this.detail = detail; }
        private void add(PlaygroundRequest trace) {
            requests++;
            if (trace.isStream()) streamingRequests++;
            if ("SUCCEEDED".equalsIgnoreCase(trace.getStatus())) succeeded++;
            if ("FAILED".equalsIgnoreCase(trace.getStatus())) failed++;
            latency.add(trace.getLatencyMs());
            input.add(trace.getInputTokens());
            output.add(trace.getOutputTokens());
            if (trace.getInputTokens() != null && trace.getOutputTokens() != null) total.add((long) trace.getInputTokens() + trace.getOutputTokens());
            else unknownTokenRequests++;
        }
        private TraceUsage view() {
            long completed = succeeded + failed;
            BigDecimal rate = completed == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(succeeded)
                    .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(completed), 1, RoundingMode.HALF_UP);
            return new TraceUsage(label, detail, requests, succeeded, failed, rate, streamingRequests, unknownTokenRequests,
                    latency.view(), input.view(), output.view(), total.view());
        }
    }

    private static final class AttemptErrorBucket {
        private final String serverName;
        private final String modelName;
        private final String errorType;
        private long count;
        private AttemptErrorBucket(String serverName, String modelName, String errorType) {
            this.serverName = serverName; this.modelName = modelName; this.errorType = errorType;
        }
    }

    private record InfrastructureLabel(String key, String title, String detail) { }

    private record RuntimeIdentity(String key, String kind, String name, String detail) { }
    private record ModelIdentity(String key, String name, String detail) { }

    public record OrganizationUsageOverview(UsageMetric total, List<UsageMetric> byProject,
                                            List<UsageMetric> byService, List<UsageMetric> byInfrastructure,
                                            List<UsageMetric> byApiKey, List<RecentRequest> recentRequests,
                                            LocalDate periodFrom, LocalDate periodTo,
                                            String scope, String scopeLabel,
                                            List<ProjectScope> availableProjects,
                                            RuntimeAnalytics runtimeAnalytics) { }

    public record ProjectScope(UUID id, String name, String access, String accessLabel) { }

    public record UsageMetric(String label, String detail, long requestCount, long succeeded, long failed,
                              long inputTokens, long outputTokens, BigDecimal estimatedCost,
                              Map<String, BigDecimal> estimatedCostByCurrency,
                              long failovers, long averageLatencyMs) { }

    public record RecentRequest(String requestId, String projectName, String serviceKey,
                                String infrastructure, String apiKeyLabel, String status,
                                long inputTokens, long outputTokens, BigDecimal estimatedCost,
                                String costCurrency, Long latencyMs, int failoverCount,
                                String errorCode, Instant startedAt, String reasoningEffort,
                                String requestedServiceTier, String actualServiceTier) { }

    public record RuntimeAnalytics(List<RuntimeUsage> byServer, List<ModelUsage> byModel,
                                   List<Breakdown> byRequestType, List<CapabilityUsage> byCapability,
                                   List<Breakdown> byStreamMode,
                                   List<Breakdown> byReasoningEffort, List<Breakdown> byServiceTier,
                                   List<Breakdown> byFailureCode, List<AttemptFailure> byAttemptFailure,
                                   List<TimeSeriesPoint> timeSeries, String timeSeriesGranularity,
                                   PlaygroundAnalytics playground) { }

    public record RuntimeUsage(String serverId, String serverType, String serverName, String detail,
                               RequestMetrics requests, AttemptMetrics attempts) { }
    public record ModelUsage(String deploymentId, String modelName, String detail,
                             RequestMetrics requests, AttemptMetrics attempts) { }
    public record RequestMetrics(long requestCount, long completedRequests, long succeeded, long failed,
                                 long inProgress, BigDecimal successRatePercent, long failoverRequests,
                                 long failoverAttempts, long inputTokens, long outputTokens,
                                 long reasoningTokens, long cachedInputTokens, long unknownCostRequests,
                                 NumericSummary latencyMs, NumericSummary inputTokensStats,
                                 NumericSummary outputTokensStats, NumericSummary totalTokensStats,
                                 Map<String, BigDecimal> estimatedCostByCurrency) { }
    public record AttemptMetrics(long total, long succeeded, long failed, long inProgress,
                                 BigDecimal successRatePercent, NumericSummary latencyMs) { }
    public record NumericSummary(long sampleCount, BigDecimal average, Long minimum, Long maximum) { }
    public record Breakdown(String label, long requests, double percentOfRequests) { }
    public record CapabilityUsage(String label, long requests, double percentOfRequests) { }
    public record AttemptFailure(String serverName, String modelName, String errorType, long count) { }
    public record TimeSeriesPoint(String period, long requests, long succeeded, long failed, long tokens) { }
    public record PlaygroundAnalytics(TraceUsage total, List<TraceUsage> byServer, List<TraceUsage> byModel,
                                     List<Breakdown> byRequestType, List<Breakdown> byStreamMode,
                                     List<Breakdown> byErrorCode,
                                     List<TimeSeriesPoint> timeSeries) { }
    public record TraceUsage(String label, String detail, long requestCount, long succeeded, long failed,
                             BigDecimal successRatePercent, long streamingRequests, long unknownTokenRequests,
                             NumericSummary latencyMs, NumericSummary inputTokens,
                             NumericSummary outputTokens, NumericSummary totalTokens) { }
}
