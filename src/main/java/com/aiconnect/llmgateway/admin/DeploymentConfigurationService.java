package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;
import java.util.Set;

@Service
public class DeploymentConfigurationService {
    private final ModelDeploymentRepository deployments;
    private final ObjectMapper objectMapper;

    public DeploymentConfigurationService(ModelDeploymentRepository deployments, ObjectMapper objectMapper) {
        this.deployments = deployments;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ModelDeployment configure(UUID deploymentId, DeploymentConfigurationController.UpdateDeployment request) {
        ModelDeployment deployment = deployments.findById(deploymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "The model deployment does not exist."));
        deployment.configure(request.compatibilityKey(), request.enabled(), request.maxConcurrency(), request.capabilityOverridesJson());
        deployment.configureDisplayName(request.displayName());
        deployment.configurePricing(request.inputPricePerMillion(), request.outputPricePerMillion(), request.currency());
        deployment.configureFeatureSupport(validateFeatureSupport(request.featureSupportJson()));
        return deployments.save(deployment);
    }

    private String validateFeatureSupport(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode value = objectMapper.readTree(json);
            if (value == null || !value.isObject()) throw new IllegalArgumentException();
            for (String feature : List.of("vision", "thinking", "fast", "reasoningLevels")) {
                JsonNode state = value.get(feature);
                if (state != null && (!state.isTextual() || !Set.of("SUPPORTED", "UNSUPPORTED", "UNKNOWN").contains(state.asText()))) {
                    throw new IllegalArgumentException();
                }
            }
            JsonNode efforts = value.get("reasoningEfforts");
            if (efforts != null) {
                if (!efforts.isArray()) throw new IllegalArgumentException();
                Set<String> allowed = Set.of("NONE", "MINIMAL", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX");
                for (JsonNode effort : efforts) if (!effort.isTextual() || !allowed.contains(effort.asText())) throw new IllegalArgumentException();
            }
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MODEL_FEATURE_SUPPORT",
                    "Model feature support must be a JSON object with SUPPORTED, UNSUPPORTED, or UNKNOWN values and valid reasoning effort names.");
        }
    }
}
