package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_explorer;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class AdminRequestExplorerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired LlmRequestRepository requests;
    @Autowired LlmRequestAttemptRepository attempts;

    @Test
    void filtersOrganizationRequestsAndReturnsAttemptDetails() throws Exception {
        Organization organization = organizations.save(new Organization("Explorer Org"));
        Project project = projects.save(new Project(organization.getId(), "explorer-project"));
        ApiKey key = apiKeys.save(new ApiKey(project.getId(), "key", "sk_explorer_" + UUID.randomUUID(), "0".repeat(64), null));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://node:1234", null); endpoint.recordHealth(true); endpoint = endpoints.save(endpoint);
        ModelDeployment deployment = deployments.save(new ModelDeployment(endpoint.getId(), "physical", "Physical", null, null, 8192, true, 1, "[]"));
        LlmService service = services.save(new LlmService(organization.getId(), "explorer", "Explorer", FailoverPolicy.STRICT, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        LlmRequest request = requests.save(new LlmRequest("request-explorer", project.getId(), key.getId(), service, false));
        LlmRequestAttempt attempt = attempts.save(new LlmRequestAttempt(request.getId(), deployment.getId(), 1));
        attempt.fail("CONNECTION_REFUSED", "runtime down", 12, null); attempts.save(attempt);
        request.fail("MODEL_UNAVAILABLE", 503, 20, 1); requests.save(request);

        String response = mvc.perform(get("/api/admin/organizations/{organizationId}/requests", organization.getId())
                        .header("X-Admin-Token", "integration-admin-token").param("status", "FAILED").param("failoverOnly", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        assertThat(body.path("totalElements").asLong()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("requestId").asText()).isEqualTo("request-explorer");
        assertThat(body.path("items").get(0).path("attempts").get(0).path("errorType").asText()).isEqualTo("CONNECTION_REFUSED");
    }
}
