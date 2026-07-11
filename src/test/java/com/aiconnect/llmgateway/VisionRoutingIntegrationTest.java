package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.gateway.RequestCapabilityDetector;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.routing.ResolvedTarget;
import com.aiconnect.llmgateway.routing.RoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_vision_routing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class VisionRoutingIntegrationTest {
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired RoutingService routing;
    @Autowired ObjectMapper objectMapper;

    @Test
    void imageContentExcludesTextOnlyPrimaryAndSelectsVisionDeployment() throws Exception {
        Organization organization = organizations.save(new Organization("Vision Org"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "vision-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://vision-node:1234", null);
        endpoint.recordHealth(true); endpoint = endpoints.save(endpoint);
        ModelDeployment text = deployments.save(new ModelDeployment(endpoint.getId(), "text-only", "Text", null, null, 8192, true, 2, "[\"CHAT_COMPLETION\"]"));
        ModelDeployment vision = deployments.save(new ModelDeployment(endpoint.getId(), "vision-model", "Vision", null, null, 8192, true, 2, "[\"CHAT_COMPLETION\",\"VISION\"]"));
        LlmService service = services.save(new LlmService(organization.getId(), "multimodal", "Multimodal",
                FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        targets.save(new ServiceTarget(service.getId(), text.getId(), 1, 100, false, null));
        targets.save(new ServiceTarget(service.getId(), vision.getId(), 2, 100, false, null));
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]}]}
                """);

        List<ResolvedTarget> candidates = routing.candidates(service, RequestCapabilityDetector.detect(request));
        assertThat(candidates).extracting(candidate -> candidate.deployment().getProviderModelId())
                .containsExactly("vision-model");
    }
}
