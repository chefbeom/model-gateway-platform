package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.repository.ServiceTargetRepository;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EndpointAdministrationService {
    private final RuntimeEndpointRepository endpoints;
    private final InferenceNodeRepository nodes;
    private final ModelDeploymentRepository deployments;
    private final ServiceTargetRepository targets;
    private final SecretCipher cipher;
    private final AuditService audit;

    public EndpointAdministrationService(RuntimeEndpointRepository endpoints, InferenceNodeRepository nodes,
                                         ModelDeploymentRepository deployments, ServiceTargetRepository targets,
                                         SecretCipher cipher, AuditService audit) {
        this.endpoints = endpoints;
        this.nodes = nodes;
        this.deployments = deployments;
        this.targets = targets;
        this.cipher = cipher;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public EndpointDetail detail(UUID endpointId) {
        RuntimeEndpoint endpoint = endpoint(endpointId);
        InferenceNode node = node(endpoint.getNodeId());
        return EndpointDetail.from(endpoint, node);
    }

    @Transactional
    public RuntimeEndpoint update(UUID endpointId, UpdateCommand command) {
        RuntimeEndpoint endpoint = endpoint(endpointId);
        String baseUrl = command.baseUrl() == null ? endpoint.getBaseUrl() : normalizeBaseUrl(command.baseUrl());
        if (!baseUrl.equals(endpoint.getBaseUrl()) && endpoints.existsByBaseUrl(baseUrl)) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNTIME_ENDPOINT_ALREADY_EXISTS", "The runtime Base URL is already registered.");
        }
        if (command.clearApiToken() && command.apiToken() != null && !command.apiToken().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUNTIME_TOKEN_UPDATE_INVALID", "Set a new token or clear the existing token, not both.");
        }
        boolean replaceToken = command.apiToken() != null && !command.apiToken().isBlank();
        endpoint.configure(baseUrl, replaceToken ? cipher.encrypt(command.apiToken()) : null, replaceToken,
                command.clearApiToken(), command.enabled());
        RuntimeEndpoint saved = endpoints.save(endpoint);
        InferenceNode node = node(saved.getNodeId());
        audit.record(node.getOrganizationId(), CurrentActor.userIdOrNull(), "RUNTIME_ENDPOINT_UPDATED", "RUNTIME_ENDPOINT", saved.getId(),
                Map.of("baseUrl", saved.getBaseUrl(), "enabled", saved.isEnabled(), "apiTokenChanged", replaceToken || command.clearApiToken()));
        return saved;
    }

    @Transactional
    public void archive(UUID endpointId) {
        RuntimeEndpoint endpoint = endpoint(endpointId);
        List<ModelDeployment> endpointDeployments = deployments.findByRuntimeEndpointId(endpointId);
        List<UUID> deploymentIds = endpointDeployments.stream().map(ModelDeployment::getId).toList();
        int targetCount = deploymentIds.isEmpty() ? 0 : targets.findByDeploymentIdIn(deploymentIds).size();
        if (targetCount > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "RUNTIME_ENDPOINT_IN_USE",
                    "This endpoint is still referenced by " + targetCount + " service target(s). Reassign or remove those targets before deletion.");
        }
        endpoint.archive();
        endpoints.save(endpoint);
        InferenceNode node = node(endpoint.getNodeId());
        audit.record(node.getOrganizationId(), CurrentActor.userIdOrNull(), "RUNTIME_ENDPOINT_ARCHIVED", "RUNTIME_ENDPOINT", endpoint.getId(),
                Map.of("baseUrl", endpoint.getBaseUrl(), "deploymentCount", endpointDeployments.size()));
    }

    private RuntimeEndpoint endpoint(UUID id) {
        return endpoints.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "The runtime endpoint does not exist."));
    }

    private InferenceNode node(UUID id) {
        return nodes.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND", "The inference node does not exist."));
    }

    private String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
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

    public record UpdateCommand(String baseUrl, String apiToken, boolean clearApiToken, Boolean enabled) { }

    public record EndpointDetail(UUID id, UUID nodeId, String nodeName, String nodeDescription, String runtimeType,
                                 String baseUrl, boolean enabled, String healthStatus, String lastCheckedAt,
                                 boolean apiTokenConfigured) {
        static EndpointDetail from(RuntimeEndpoint endpoint, InferenceNode node) {
            return new EndpointDetail(endpoint.getId(), endpoint.getNodeId(), node.getName(), node.getDescription(),
                    endpoint.getRuntimeType().name(), endpoint.getBaseUrl(), endpoint.isEnabled(), endpoint.getHealthStatus().name(),
                    endpoint.getLastCheckedAt() == null ? null : endpoint.getLastCheckedAt().toString(), endpoint.getApiToken() != null);
        }
    }
}
