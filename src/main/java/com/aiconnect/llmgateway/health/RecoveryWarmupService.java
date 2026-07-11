package com.aiconnect.llmgateway.health;

import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
public class RecoveryWarmupService {
    private final ModelDeploymentRepository deployments;
    private final InferenceRuntimeClient runtimeClient;
    private final ObjectMapper objectMapper;
    public RecoveryWarmupService(ModelDeploymentRepository deployments, InferenceRuntimeClient runtimeClient, ObjectMapper objectMapper) {
        this.deployments = deployments; this.runtimeClient = runtimeClient; this.objectMapper = objectMapper;
    }
    public boolean warm(RuntimeEndpoint endpoint) {
        ModelDeployment deployment = deployments.findByRuntimeEndpointId(endpoint.getId()).stream()
                .filter(candidate -> candidate.isEnabled() && candidate.isLoaded()).findFirst().orElse(null);
        if (deployment == null) return false;
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", deployment.getProviderModelId()); request.put("stream", false); request.put("max_tokens", 1); request.put("temperature", 0);
        request.putArray("messages").addObject().put("role", "user").put("content", "Reply OK");
        try { RuntimeResult result = runtimeClient.chatCompletion(endpoint, request); return result.isSuccessful(); }
        catch (RuntimeUnavailableException exception) { return false; }
    }
}
