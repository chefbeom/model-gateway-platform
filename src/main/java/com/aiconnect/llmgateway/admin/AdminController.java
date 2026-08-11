package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.identity.CurrentActor;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final ControlPlaneService controlPlane;
    private final ApiKeyService apiKeyService;
    private final ApiKeyRepository apiKeys;

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
    @PostMapping("/projects/{projectId}/api-keys") public IssuedApiKey createApiKey(@PathVariable UUID projectId, @Valid @RequestBody AdminDtos.CreateApiKey request) { return apiKeyService.issue(projectId, request.name(), request.expiresAt(), CurrentActor.userIdOrNull()); }
    @DeleteMapping("/api-keys/{apiKeyId}") public void revokeApiKey(@PathVariable UUID apiKeyId) { apiKeyService.revoke(apiKeyId); }
    @GetMapping("/projects/{projectId}/api-keys") public List<ApiKeyView> apiKeys(@PathVariable UUID projectId) { return apiKeys.findByProjectId(projectId).stream().map(ApiKeyView::from).toList(); }
    @PostMapping("/runtime-endpoints/{endpointId}/probe") public ControlPlaneService.ProbeResult probe(@PathVariable UUID endpointId) { return controlPlane.probe(endpointId); }
    @PostMapping("/runtime-endpoints/{endpointId}/sync-models") public List<DeploymentView> syncModels(@PathVariable UUID endpointId) { return controlPlane.syncModels(endpointId).stream().map(DeploymentView::from).toList(); }
    @GetMapping("/runtime-endpoints") public List<EndpointView> endpoints() { return controlPlane.endpoints().stream().map(EndpointView::from).toList(); }
    @GetMapping("/runtime-endpoints/{endpointId}/deployments") public List<DeploymentView> deployments(@PathVariable UUID endpointId) { return controlPlane.deployments(endpointId).stream().map(DeploymentView::from).toList(); }

    public record OrganizationView(UUID id, String name, String status, Instant createdAt) { static OrganizationView from(Organization item) { return new OrganizationView(item.getId(), item.getName(), item.getStatus(), item.getCreatedAt()); } }
    public record ProjectView(UUID id, UUID organizationId, UUID teamId, String name, String status) { static ProjectView from(Project item) { return new ProjectView(item.getId(), item.getOrganizationId(), item.getTeamId(), item.getName(), item.getStatus()); } }
    public record NodeView(UUID id, UUID organizationId, String name, HealthStatus status) { static NodeView from(InferenceNode item) { return new NodeView(item.getId(), item.getOrganizationId(), item.getName(), item.getStatus()); } }
    public record EndpointView(UUID id, UUID nodeId, String displayName, RuntimeType runtimeType, String baseUrl, boolean enabled, HealthStatus healthStatus, Instant lastCheckedAt) { static EndpointView from(RuntimeEndpoint item) { return new EndpointView(item.getId(), item.getNodeId(), item.getDisplayName(), item.getRuntimeType(), item.getBaseUrl(), item.isEnabled(), item.getHealthStatus(), item.getLastCheckedAt()); } }
    public record DeploymentView(UUID id, UUID runtimeEndpointId, String providerModelId, String compatibilityKey, String displayName, String modelFamily, String quantization, Integer contextLength, boolean loaded, boolean enabled, HealthStatus healthStatus, int maxConcurrency, String capabilitiesJson, String capabilityOverridesJson) { static DeploymentView from(ModelDeployment item) { return new DeploymentView(item.getId(), item.getRuntimeEndpointId(), item.getProviderModelId(), item.getCompatibilityKey(), item.getDisplayName(), item.getModelFamily(), item.getQuantization(), item.getContextLength(), item.isLoaded(), item.isEnabled(), item.getHealthStatus(), item.getMaxConcurrency(), item.getCapabilitiesJson(), item.getCapabilityOverridesJson()); } }
    public record ServiceView(UUID id, UUID organizationId, String serviceKey, String displayName, BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, Currency currency, FailoverPolicy failoverPolicy, RetryPolicy retryPolicy, boolean allowDegraded, boolean enabled) { static ServiceView from(LlmService item) { return new ServiceView(item.getId(), item.getOrganizationId(), item.getServiceKey(), item.getDisplayName(), item.getInputPricePerMillion(), item.getOutputPricePerMillion(), item.getCurrency(), item.getFailoverPolicy(), item.getRetryPolicy(), item.isAllowDegraded(), item.isEnabled()); } }
    public record TargetView(UUID id, UUID serviceId, UUID deploymentId, int priority, boolean degraded) { static TargetView from(ServiceTarget item) { return new TargetView(item.getId(), item.getServiceId(), item.getDeploymentId(), item.getPriority(), item.isDegraded()); } }
    public record ApiKeyView(UUID id, String name, String keyPrefix, ApiKeyStatus status, Instant expiresAt, Instant lastUsedAt, Instant createdAt) { static ApiKeyView from(ApiKey item) { return new ApiKeyView(item.getId(), item.getName(), item.getKeyPrefix(), item.getStatus(), item.getExpiresAt(), item.getLastUsedAt(), item.getCreatedAt()); } }
}
