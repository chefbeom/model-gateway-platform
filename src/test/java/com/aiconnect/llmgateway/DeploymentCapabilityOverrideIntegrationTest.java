package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.admin.DeploymentConfigurationController;
import com.aiconnect.llmgateway.admin.DeploymentConfigurationService;
import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.routing.RoutingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_capability_override;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DeploymentCapabilityOverrideIntegrationTest {
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired DeploymentConfigurationService configuration;
    @Autowired RoutingService routing;

    @Test
    void administratorVerifiedCapabilitySurvivesAsSeparateRoutingOverride() {
        Organization organization = organizations.save(new Organization("Override Org"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "override-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://override-node:1234", null);
        endpoint.recordHealth(true); endpoint = endpoints.save(endpoint);
        ModelDeployment deployment = deployments.save(new ModelDeployment(endpoint.getId(), "physical-model", "model-contract",
                "Physical", null, null, 8192, true, 2, "[\"CHAT_COMPLETION\",\"STREAMING\"]"));
        LlmService service = services.save(new LlmService(organization.getId(), "structured-service", "Structured",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[\"STRUCTURED_OUTPUT\"]",
                BigDecimal.ZERO, BigDecimal.ZERO));
        targets.save(new ServiceTarget(service.getId(), deployment.getId(), 1, 100, false, null));

        assertThat(routing.candidates(service, Set.of())).isEmpty();

        configuration.configure(deployment.getId(), new DeploymentConfigurationController.UpdateDeployment(
                "verified-contract", true, 3, "[\"STRUCTURED_OUTPUT\"]"));

        ModelDeployment configured = deployments.findById(deployment.getId()).orElseThrow();
        assertThat(configured.getCompatibilityKey()).isEqualTo("verified-contract");
        assertThat(configured.getMaxConcurrency()).isEqualTo(3);
        assertThat(configured.getCapabilityOverridesJson()).contains("STRUCTURED_OUTPUT");
        assertThat(routing.candidates(service, Set.of())).hasSize(1);
    }
}
