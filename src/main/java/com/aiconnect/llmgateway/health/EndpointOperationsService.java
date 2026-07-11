package com.aiconnect.llmgateway.health;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

@Service
public class EndpointOperationsService {
    private final RuntimeEndpointRepository endpoints;
    private final InferenceNodeRepository nodes;
    private final AuditService audit;
    public EndpointOperationsService(RuntimeEndpointRepository endpoints, InferenceNodeRepository nodes, AuditService audit) { this.endpoints = endpoints; this.nodes = nodes; this.audit = audit; }
    @Transactional
    public RuntimeEndpoint drain(UUID endpointId) {
        RuntimeEndpoint endpoint = require(endpointId); endpoint.beginDraining(); record(endpoint, "RUNTIME_ENDPOINT_DRAINING"); return endpoint;
    }
    @Transactional
    public RuntimeEndpoint resume(UUID endpointId) {
        RuntimeEndpoint endpoint = require(endpointId); endpoint.beginRecovery(); record(endpoint, "RUNTIME_ENDPOINT_RECOVERY_REQUESTED"); return endpoint;
    }
    private RuntimeEndpoint require(UUID id) { return endpoints.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "The runtime endpoint does not exist.")); }
    private void record(RuntimeEndpoint endpoint, String action) {
        UUID organizationId = nodes.findById(endpoint.getNodeId()).map(node -> node.getOrganizationId()).orElse(null);
        audit.record(organizationId, CurrentActor.userIdOrNull(), action, "RUNTIME_ENDPOINT", endpoint.getId(), Map.of("state", endpoint.getHealthStatus().name()));
    }
}
