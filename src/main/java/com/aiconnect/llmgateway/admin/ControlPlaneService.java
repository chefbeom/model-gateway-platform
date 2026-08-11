package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ControlPlaneService {
    private final OrganizationRepository organizations;
    private final ProjectRepository projects;
    private final InferenceNodeRepository nodes;
    private final RuntimeEndpointRepository endpoints;
    private final ModelDeploymentRepository deployments;
    private final ExternalProviderRepository externalProviders;
    private final LlmServiceRepository services;
    private final ProjectServiceAccessRepository access;
    private final ServiceTargetRepository targets;
    private final TeamRepository teams;
    private final SecretCipher secretCipher;
    private final InferenceRuntimeClient runtimeClient;
    private final LmStudioModelDiscovery modelDiscovery;

    public ControlPlaneService(OrganizationRepository organizations, ProjectRepository projects, InferenceNodeRepository nodes,
                               RuntimeEndpointRepository endpoints, ModelDeploymentRepository deployments,
                               ExternalProviderRepository externalProviders, LlmServiceRepository services, ProjectServiceAccessRepository access,
                               ServiceTargetRepository targets, TeamRepository teams, SecretCipher secretCipher,
                               InferenceRuntimeClient runtimeClient, LmStudioModelDiscovery modelDiscovery) {
        this.organizations = organizations;
        this.projects = projects;
        this.nodes = nodes;
        this.endpoints = endpoints;
        this.deployments = deployments;
        this.externalProviders = externalProviders;
        this.services = services;
        this.access = access;
        this.targets = targets;
        this.teams = teams;
        this.secretCipher = secretCipher;
        this.runtimeClient = runtimeClient;
        this.modelDiscovery = modelDiscovery;
    }

    @Transactional
    public Organization create(AdminDtos.CreateOrganization request) {
        return organizations.save(new Organization(request.name()));
    }

    @Transactional
    public Project create(AdminDtos.CreateProject request) {
        requireOrganization(request.organizationId());
        if (request.teamId() != null) requireTeam(request.organizationId(), request.teamId());
        return projects.save(new Project(request.organizationId(), request.teamId(), request.name()));
    }

    @Transactional
    public InferenceNode create(AdminDtos.CreateNode request) {
        requireOrganization(request.organizationId());
        return nodes.save(new InferenceNode(request.organizationId(), request.name(), request.description(),
                defaulted(request.connectionMode(), "DIRECT"), request.labelsJson()));
    }

    @Transactional
    public RuntimeEndpoint create(AdminDtos.CreateEndpoint request) {
        requireNode(request.nodeId());
        String baseUrl = normalizedBaseUrl(request.baseUrl());
        if (endpoints.existsByBaseUrl(baseUrl)) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNTIME_ENDPOINT_ALREADY_EXISTS", "The runtime Base URL is already registered.");
        }
        try {
            return endpoints.saveAndFlush(new RuntimeEndpoint(request.nodeId(), request.displayName(), request.runtimeType(), baseUrl,
                    secretCipher.encrypt(request.apiToken())));
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNTIME_ENDPOINT_ALREADY_EXISTS", "The runtime Base URL is already registered.");
        }
    }

    @Transactional
    public ModelDeployment create(AdminDtos.CreateDeployment request) {
        requireEndpoint(request.runtimeEndpointId());
        return deployments.save(new ModelDeployment(request.runtimeEndpointId(), request.providerModelId(), request.compatibilityKey(),
                request.displayName(), request.modelFamily(), request.quantization(), request.contextLength(), true,
                request.maxConcurrency() == null ? 1 : request.maxConcurrency(), request.capabilitiesJson()));
    }

    @Transactional
    public LlmService create(AdminDtos.CreateService request) {
        requireOrganization(request.organizationId());
        return services.save(new LlmService(request.organizationId(), request.serviceKey(), request.displayName(),
                request.failoverPolicy() == null ? FailoverPolicy.STRICT : request.failoverPolicy(),
                request.retryPolicy() == null ? RetryPolicy.SAFE : request.retryPolicy(), request.allowDegraded(),
                request.requiredCapabilitiesJson(), zeroIfNull(request.inputPricePerMillion()), zeroIfNull(request.outputPricePerMillion()),
                request.currency() == null ? Currency.KRW : request.currency()));
    }

    @Transactional
    public void grantAccess(UUID projectId, UUID serviceId) {
        Project project = requireProject(projectId);
        LlmService service = requireService(serviceId);
        if (!project.getOrganizationId().equals(service.getOrganizationId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "A project can only access a service in its organization.");
        }
        access.save(new ProjectServiceAccess(projectId, serviceId));
    }

    @Transactional
    public ServiceTarget addTarget(UUID serviceId, AdminDtos.CreateTarget request) {
        LlmService service = requireService(serviceId);
        ModelDeployment deployment = requireDeployment(request.deploymentId());
        UUID targetOrganizationId;
        if (deployment.isExternal()) {
            ExternalProvider provider = externalProviders.findById(deployment.getExternalProviderId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXTERNAL_PROVIDER_NOT_FOUND",
                            "The external provider does not exist."));
            targetOrganizationId = provider.getOrganizationId();
        } else {
            RuntimeEndpoint endpoint = requireEndpoint(deployment.getRuntimeEndpointId());
            targetOrganizationId = requireNode(endpoint.getNodeId()).getOrganizationId();
        }
        if (!service.getOrganizationId().equals(targetOrganizationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "A service target must belong to the service organization.");
        }
        return targets.save(new ServiceTarget(serviceId, deployment.getId(), request.priority(),
                request.weight() == null ? 100 : request.weight(), request.degraded(), request.maxConcurrencyOverride()));
    }

    @Transactional
    public ProbeResult probe(UUID endpointId) {
        RuntimeEndpoint endpoint = requireEndpoint(endpointId);
        try {
            RuntimeResult result = runtimeClient.listModels(endpoint);
            boolean healthy = result.isSuccessful();
            endpoint.recordHealth(healthy);
            endpoints.save(endpoint);
            List<DiscoveredRuntimeModel> discovered = modelDiscovery.discover(result.body());
            syncDeploymentHealth(endpoint, discovered, healthy);
            return healthy
                    ? new ProbeResult(true, result.statusCode(), modelIds(discovered), null)
                    : new ProbeResult(false, result.statusCode(), modelIds(discovered),
                    "LM Studio returned HTTP " + result.statusCode() + ".");
        } catch (RuntimeUnavailableException exception) {
            endpoint.recordHealth(false);
            endpoints.save(endpoint);
            syncDeploymentHealth(endpoint, List.of(), false);
            String reason = exception.getMessage();
            return new ProbeResult(false, 0, List.of(),
                    reason == null || reason.isBlank() ? "The runtime endpoint is unreachable." : reason);
        }
    }

    @Transactional
    public List<ModelDeployment> syncModels(UUID endpointId) {
        RuntimeEndpoint endpoint = requireEndpoint(endpointId);
        RuntimeResult result = runtimeClient.listModels(endpoint);
        if (!result.isSuccessful()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "RUNTIME_PROBE_FAILED", "The runtime rejected the model-list request.");
        }
        endpoint.recordHealth(true);
        endpoints.save(endpoint);
        List<DiscoveredRuntimeModel> discovered = modelDiscovery.discover(result.body());
        Map<String, ModelDeployment> existing = deployments.findByRuntimeEndpointId(endpointId).stream()
                .collect(Collectors.toMap(ModelDeployment::getProviderModelId, Function.identity()));
        Set<String> seen = new HashSet<>();
        List<ModelDeployment> created = new ArrayList<>();
        for (DiscoveredRuntimeModel model : discovered) {
            seen.add(model.providerModelId());
            ModelDeployment deployment = existing.get(model.providerModelId());
            if (deployment == null) {
                deployment = new ModelDeployment(endpointId, model.providerModelId(), model.compatibilityKey(), model.displayName(),
                        model.modelFamily(), model.quantization(), model.contextLength(), model.loaded(), model.maxConcurrency(), model.capabilitiesJson());
                deployment.synchronize(model.displayName(), model.modelFamily(), model.quantization(), model.contextLength(),
                        model.loaded(), model.maxConcurrency(), model.capabilitiesJson(), model.metadataJson());
                created.add(deployments.save(deployment));
            } else {
                deployment.synchronize(model.displayName(), model.modelFamily(), model.quantization(), model.contextLength(),
                        model.loaded(), model.maxConcurrency(), model.capabilitiesJson(), model.metadataJson());
                deployments.save(deployment);
            }
        }
        for (ModelDeployment deployment : existing.values()) {
            if (!seen.contains(deployment.getProviderModelId())) {
                deployment.markUnavailable();
                deployments.save(deployment);
            }
        }
        return created;
    }

    public List<RuntimeEndpoint> endpoints() { return endpoints.findAll(); }
    public List<ModelDeployment> deployments(UUID endpointId) { return deployments.findByRuntimeEndpointId(endpointId); }

    private void syncDeploymentHealth(RuntimeEndpoint endpoint, List<DiscoveredRuntimeModel> discovered, boolean healthy) {
        Set<String> loaded = discovered.stream().filter(DiscoveredRuntimeModel::loaded)
                .map(DiscoveredRuntimeModel::providerModelId).collect(Collectors.toSet());
        for (ModelDeployment deployment : deployments.findByRuntimeEndpointId(endpoint.getId())) {
            deployment.recordHealth(healthy && loaded.contains(deployment.getProviderModelId()));
        }
    }

    private List<String> modelIds(List<DiscoveredRuntimeModel> discovered) {
        return discovered.stream().map(DiscoveredRuntimeModel::providerModelId).toList();
    }

    private Organization requireOrganization(UUID id) {
        return organizations.findById(id).orElseThrow(() -> notFound("ORGANIZATION_NOT_FOUND", "The organization does not exist."));
    }

    private Team requireTeam(UUID organizationId, UUID teamId) {
        Team team = teams.findById(teamId).orElseThrow(() -> notFound("TEAM_NOT_FOUND", "The team does not exist."));
        if (!team.getOrganizationId().equals(organizationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "The team belongs to another organization.");
        }
        return team;
    }

    private Project requireProject(UUID id) { return projects.findById(id).orElseThrow(() -> notFound("PROJECT_NOT_FOUND", "The project does not exist.")); }
    private InferenceNode requireNode(UUID id) { return nodes.findById(id).orElseThrow(() -> notFound("NODE_NOT_FOUND", "The node does not exist.")); }
    private RuntimeEndpoint requireEndpoint(UUID id) { return endpoints.findById(id).orElseThrow(() -> notFound("ENDPOINT_NOT_FOUND", "The runtime endpoint does not exist.")); }
    private ModelDeployment requireDeployment(UUID id) { return deployments.findById(id).orElseThrow(() -> notFound("DEPLOYMENT_NOT_FOUND", "The model deployment does not exist.")); }
    private LlmService requireService(UUID id) { return services.findById(id).orElseThrow(() -> notFound("SERVICE_NOT_FOUND", "The service does not exist.")); }
    private ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
    private String defaulted(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private BigDecimal zeroIfNull(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private String normalizedBaseUrl(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RUNTIME_BASE_URL",
                    "The runtime Base URL must be an absolute HTTP(S) URL without credentials, query, or fragment.");
        }
    }

    public record ProbeResult(boolean reachable, int httpStatus, List<String> modelIds, String errorMessage) {
        /** Preserves compatibility for existing callers that only need reachability, status, and discovered model IDs. */
        public ProbeResult(boolean reachable, int httpStatus, List<String> modelIds) {
            this(reachable, httpStatus, modelIds, null);
        }
    }
}
