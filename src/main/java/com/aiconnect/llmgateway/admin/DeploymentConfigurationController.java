package com.aiconnect.llmgateway.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.math.BigDecimal;

import com.aiconnect.llmgateway.domain.Currency;

@RestController
@RequestMapping("/api/admin/model-deployments")
public class DeploymentConfigurationController {
    private final DeploymentConfigurationService configuration;

    public DeploymentConfigurationController(DeploymentConfigurationService configuration) {
        this.configuration = configuration;
    }

    @PatchMapping("/{deploymentId}")
    public AdminController.DeploymentView configure(@PathVariable UUID deploymentId,
                                                     @Valid @RequestBody UpdateDeployment request) {
        return AdminController.DeploymentView.from(configuration.configure(deploymentId, request));
    }

    public record UpdateDeployment(
            @Size(max = 200) String displayName,
            @Size(max = 500) String compatibilityKey,
            Boolean enabled,
            @Min(1) Integer maxConcurrency,
            String capabilityOverridesJson,
            @jakarta.validation.constraints.DecimalMin("0") BigDecimal inputPricePerMillion,
            @jakarta.validation.constraints.DecimalMin("0") BigDecimal outputPricePerMillion,
            Currency currency
    ) {
        public UpdateDeployment(String compatibilityKey, Boolean enabled, Integer maxConcurrency, String capabilityOverridesJson) {
            this(null, compatibilityKey, enabled, maxConcurrency, capabilityOverridesJson, null, null, null);
        }
    }
}
