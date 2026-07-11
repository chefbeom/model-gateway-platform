package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.admin.RoutingPolicyController;
import com.aiconnect.llmgateway.admin.RoutingPolicyService;
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
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_routing_management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class RoutingPolicyManagementIntegrationTest {
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired RoutingPolicyService policies;
    @Autowired RoutingService routing;

    @Test
    void changesServicePolicyAndTargetPriorityWithoutChangingLogicalModelKey() {
        Organization organization = organizations.save(new Organization("Routing Management Org"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "routing-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://routing-node:1234", null);
        endpoint.recordHealth(true); endpoint = endpoints.save(endpoint);
        ModelDeployment deployment = deployments.save(new ModelDeployment(endpoint.getId(), "physical", "Physical", null, null, 8192, true, 2, "[]"));
        LlmService service = services.save(new LlmService(organization.getId(), "stable-logical-key", "Before",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        ServiceTarget target = targets.save(new ServiceTarget(service.getId(), deployment.getId(), 1, 100, false, null));

        policies.configureService(service.getId(), new RoutingPolicyController.UpdateService(
                "After", FailoverPolicy.COMPATIBLE, RetryPolicy.AGGRESSIVE, true, "[]",
                BigDecimal.valueOf(10), BigDecimal.valueOf(20), true));
        policies.configureTarget(service.getId(), target.getId(), new RoutingPolicyController.UpdateTarget(
                2, 50, true, false, 1));

        LlmService changedService = services.findById(service.getId()).orElseThrow();
        ServiceTarget changedTarget = targets.findById(target.getId()).orElseThrow();
        assertThat(changedService.getServiceKey()).isEqualTo("stable-logical-key");
        assertThat(changedService.getFailoverPolicy()).isEqualTo(FailoverPolicy.COMPATIBLE);
        assertThat(changedService.getRetryPolicy()).isEqualTo(RetryPolicy.AGGRESSIVE);
        assertThat(changedService.getInputPricePerMillion()).isEqualByComparingTo("10");
        assertThat(changedTarget.getPriority()).isEqualTo(2);
        assertThat(changedTarget.getWeight()).isEqualTo(50);
        assertThat(changedTarget.isEnabled()).isFalse();
        assertThat(routing.candidates(changedService, Set.of())).isEmpty();

        policies.deleteTarget(service.getId(), target.getId());
        assertThat(targets.findById(target.getId())).isEmpty();
    }
}
