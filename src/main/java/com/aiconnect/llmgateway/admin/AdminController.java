package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final ControlPlaneService controlPlane;
    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeys;
    @Autowired(required = false)
    private ProjectRepository projects;
    @Autowired(required = false)
    private AuditService audit;

    public AdminController(ControlPlaneService controlPlane, ApiKeyService apiKeyService, ApiKeyRepository apiKeys) {
        this.controlPlane = controlPlane;
        this.apiKeyService = apiKeyService;
        this.apiKeys = apiKeys;
    }

    @PostMapping("/organizations") public OrganizationView createOrganization(@Valid @RequestBody AdminDtos.CreateOrganization request) { return OrganizationView.from(controlPlane.create(request)); }
    @PostMapping("/projects") public ProjectView createProject(@Valid @RequestBody AdminDtos.CreateProject request) { return ProjectView.from(controlPlane.create(request)); }
    @PostMapping("/nodes") public NodeView createNode(@Valid @RequestBody AdminDtos.CreateNode request) { return NodeView.from(controlPlane.create(request)); }
    @PostMapping("/runtime-endpoints") public EndpointView createEndpoint(@Valid @RequestBody AdminDtos.CreateEndpoint request) { return EndpointView.from(controlPlane.create(request)); }
    @PostMapping("/model-deployments") public DeploymentView createDeployment(@Valid @RequestBody AdminDtos.CreateDeployment request) { return DeploymentView.from(controlPlane.create(request)); }
    @PostMapping("/services") public ServiceView createService(@Valid @RequestBody AdminDtos.CreateService request) { return ServiceView.from(controlPlane.create(request)); }

    @PostMapping("/projects/{projectId}/service-access")
    public void grantServiceAccess(@PathVariable UUID projectId, @Valid @RequestBody AdminDtos.GrantServiceAccess request) { controlPlane.grantAccess(projectId, request.serviceId()); }
    @PostMapping("/services/{serviceId}/targets") public TargetView addTarget(@PathVariable UUID serviceId, @Valid @RequestBody AdminDtos.CreateTarget request) { return TargetView.from(controlPlane.addTarget(serviceId, request)); }
    @PostMapping("/projects/{projectId}/api-keys") public IssuedApiKey createApiKey(@PathVariable UUID projectId, @Valid @RequestBody AdminDtos.CreateApiKey request) { return apiKeyService.issue(projectId, request.name(), request.expiresAt(), CurrentActor.userIdOrNull(), request.dataBoundaryPolicy()); }
    @DeleteMapping("/api-keys/{apiKeyId}") public void revokeApiKey(@PathVariable UUID apiKeyId) { apiKeyService.revoke(apiKeyId); }
    @GetMapping("/projects/{projectId}/api-keys") public List<ApiKeyView> apiKeys(@PathVariable UUID projectId) {
        DataBoundaryPolicy projectBoundary = projects == null ? DataBoundaryPolicy.EXTERNAL_ALLOWED
                : projects.findById(projectId).map(Project::effectiveDataBoundaryPolicy).orElse(DataBoundaryPolicy.EXTERNAL_ALLOWED);
        return apiKeys.findByProjectId(projectId).stream().map(item -> ApiKeyView.from(item, projectBoundary)).toList();
    }
    @PatchMapping("/projects/{projectId}/api-keys/{apiKeyId}/policy")
    public ApiKeyView updateApiKeyPolicy(@PathVariable UUID projectId, @PathVariable UUID apiKeyId,
                                         @Valid @RequestBody UpdateApiKeyPolicy request) {
        Project project = projects == null ? null : projects.findById(projectId).orElse(null);
        if (project == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist.");
        }
        ApiKey key = apiKeys.findById(apiKeyId)
                .filter(item -> projectId.equals(item.getProjectId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "The API key does not exist in this project."));
        DataBoundaryPolicy projectEffective = project.effectiveDataBoundaryPolicy();
        if (request.dataBoundaryPolicy().exceeds(projectEffective)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DATA_BOUNDARY_RELAXATION_DENIED",
                    "API key data boundary cannot exceed the project data boundary policy.");
        }
        key.configureDataBoundaryPolicy(request.dataBoundaryPolicy());
        ApiKey saved = apiKeys.save(key);
        if (audit != null) {
            audit.record(project.getOrganizationId(), CurrentActor.userIdOrNull(), "API_KEY_BOUNDARY_UPDATED", "API_KEY", saved.getId(),
                    Map.of("projectId", projectId.toString(), "dataBoundaryPolicy", saved.getDataBoundaryPolicy().name()));
        }
        return ApiKeyView.from(saved, projectEffective);
    }
    @PostMapping("/runtime-endpoints/{endpointId}/probe") public ControlPlaneService.ProbeResult probe(@PathVariable UUID endpointId) { return controlPlane.probe(endpointId); }
    @PostMapping("/runtime-endpoints/{endpointId}/sync-models") public List<DeploymentView> syncModels(@PathVariable UUID endpointId) { return controlPlane.syncModels(endpointId).stream().map(DeploymentView::from).toList(); }
    @GetMapping("/runtime-endpoints") public List<EndpointView> endpoints() { return controlPlane.endpoints().stream().map(EndpointView::from).toList(); }
    @GetMapping("/runtime-endpoints/{endpointId}/deployments") public List<DeploymentView> deployments(@PathVariable UUID endpointId) { return controlPlane.deployments(endpointId).stream().map(DeploymentView::from).toList(); }

    public record OrganizationView(UUID id, String name, String status, Instant createdAt) { static OrganizationView from(Organization item) { return new OrganizationView(item.getId(), item.getName(), item.getStatus(), item.getCreatedAt()); } }
    public record ProjectView(UUID id, UUID organizationId, UUID teamId, String name, String status, boolean externalAiBlocked, DataBoundaryPolicy dataBoundaryPolicy, DataBoundaryPolicy maxDataBoundaryPolicy) { static ProjectView from(Project item) { return new ProjectView(item.getId(), item.getOrganizationId(), item.getTeamId(), item.getName(), item.getStatus(), item.isExternalAiBlocked(), item.getDataBoundaryPolicy(), item.getMaxDataBoundaryPolicy()); } }
    public record NodeView(UUID id, UUID organizationId, String name, HealthStatus status) { static NodeView from(InferenceNode item) { return new NodeView(item.getId(), item.getOrganizationId(), item.getName(), item.getStatus()); } }
    public record EndpointView(UUID id, UUID nodeId, String displayName, RuntimeType runtimeType, String baseUrl, boolean enabled, HealthStatus healthStatus, Instant lastCheckedAt, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, Currency currency) { static EndpointView from(RuntimeEndpoint item) { return new EndpointView(item.getId(), item.getNodeId(), item.getDisplayName(), item.getRuntimeType(), item.getBaseUrl(), item.isEnabled(), item.getHealthStatus(), item.getLastCheckedAt(), item.getInputPricePerMillion(), item.getOutputPricePerMillion(), item.getCurrency()); } }
    public record DeploymentView(UUID id, UUID runtimeEndpointId, UUID externalProviderId, String providerModelId, String compatibilityKey, String displayName, String modelFamily, String quantization, Integer contextLength, boolean loaded, boolean enabled, HealthStatus healthStatus, int maxConcurrency, String capabilitiesJson, String capabilityOverridesJson, String featureSupportJson, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, Currency currency, String metadataJson) { static DeploymentView from(ModelDeployment item) { return new DeploymentView(item.getId(), item.getRuntimeEndpointId(), item.getExternalProviderId(), item.getProviderModelId(), item.getCompatibilityKey(), item.getDisplayName(), item.getModelFamily(), item.getQuantization(), item.getContextLength(), item.isLoaded(), item.isEnabled(), item.getHealthStatus(), item.getMaxConcurrency(), item.getCapabilitiesJson(), item.getCapabilityOverridesJson(), item.getFeatureSupportJson(), item.getProviderInputPricePerMillion(), item.getProviderOutputPricePerMillion(), item.getProviderPriceCurrency(), item.getMetadataJson()); } }
    public record ServiceView(UUID id, UUID organizationId, String serviceKey, String displayName, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, Currency currency, FailoverPolicy failoverPolicy, RetryPolicy retryPolicy, boolean allowDegraded, boolean enabled, TemperaturePolicy temperaturePolicy, BigDecimal temperatureValue, ReasoningEffort reasoningEffort, boolean openAiFastMode, DataBoundaryPolicy dataBoundaryPolicy) { static ServiceView from(LlmService item) { return new ServiceView(item.getId(), item.getOrganizationId(), item.getServiceKey(), item.getDisplayName(), item.getInputPricePerMillion(), item.getOutputPricePerMillion(), item.getCurrency(), item.getFailoverPolicy(), item.getRetryPolicy(), item.isAllowDegraded(), item.isEnabled(), item.getTemperaturePolicy(), item.getTemperatureValue(), item.getReasoningEffort(), item.isOpenAiFastMode(), item.getDataBoundaryPolicy()); } }
    public record TargetView(UUID id, UUID serviceId, UUID deploymentId, int priority, boolean degraded,
                             boolean followModelChanges) { static TargetView from(ServiceTarget item) { return new TargetView(item.getId(), item.getServiceId(), item.getDeploymentId(), item.getPriority(), item.isDegraded(), item.isFollowModelChanges()); } }
    public record UpdateApiKeyPolicy(@NotNull DataBoundaryPolicy dataBoundaryPolicy) { }
    public record ApiKeyView(UUID id, String name, String keyPrefix, ApiKeyStatus status, Instant expiresAt, Instant lastUsedAt, Instant createdAt, UUID issuedByUserId, DataBoundaryPolicy dataBoundaryPolicy, DataBoundaryPolicy effectiveDataBoundaryPolicy) {
        static ApiKeyView from(ApiKey item) {
            return from(item, DataBoundaryPolicy.EXTERNAL_ALLOWED);
        }

        static ApiKeyView from(ApiKey item, DataBoundaryPolicy projectBoundary) {
            return new ApiKeyView(item.getId(), item.getName(), item.getKeyPrefix(), item.getStatus(),
                    item.getExpiresAt(), item.getLastUsedAt(), item.getCreatedAt(), item.getIssuedByUserId(),
                    item.getDataBoundaryPolicy(), DataBoundaryPolicy.strictest(projectBoundary, item.getDataBoundaryPolicy()));
        }
    }
}
