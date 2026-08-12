package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DeploymentConfigurationService {
    private final ModelDeploymentRepository deployments;

    public DeploymentConfigurationService(ModelDeploymentRepository deployments) {
        this.deployments = deployments;
    }

    @Transactional
    public ModelDeployment configure(UUID deploymentId, DeploymentConfigurationController.UpdateDeployment request) {
        ModelDeployment deployment = deployments.findById(deploymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "The model deployment does not exist."));
        deployment.configure(request.compatibilityKey(), request.enabled(), request.maxConcurrency(), request.capabilityOverridesJson());
        deployment.configureDisplayName(request.displayName());
        deployment.configurePricing(request.inputPricePerMillion(), request.outputPricePerMillion(), request.currency());
        return deployments.save(deployment);
    }
}
