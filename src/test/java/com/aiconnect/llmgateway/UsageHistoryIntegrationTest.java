package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_usage_history;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class UsageHistoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyService apiKeyService;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired LlmRequestRepository requests;

    @Test
    void returnsLogicalAndActualModelLabelsWithoutIncludingAnotherProject() throws Exception {
        Organization organization = organizations.save(new Organization("Usage Org"));
        Project project = projects.save(new Project(organization.getId(), "visible"));
        Project other = projects.save(new Project(organization.getId(), "other"));
        IssuedApiKey key = apiKeyService.issue(project.getId(), "visible-key", null);
        IssuedApiKey otherKey = apiKeyService.issue(other.getId(), "other-key", null);
        ApiKey visibleApiKey = apiKeyService.authenticate("Bearer " + key.secret()).apiKey();
        ApiKey otherApiKey = apiKeyService.authenticate("Bearer " + otherKey.secret()).apiKey();
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "usage-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://usage-node:1234", null);
        endpoint.recordHealth(true); endpoint = endpoints.save(endpoint);
        ModelDeployment deployment = deployments.save(new ModelDeployment(endpoint.getId(), "provider/model", "Production Model", null, "Q8", 8192, true, 2, "[]"));
        LlmService service = services.save(new LlmService(organization.getId(), "logical-analysis", "Logical Analysis",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.valueOf(100), BigDecimal.valueOf(200)));

        LlmRequest visible = new LlmRequest(UUID.randomUUID().toString(), project.getId(), visibleApiKey.getId(), service, true);
        visible.succeed(deployment.getId(), 3, 2, 50, 200, 1); requests.save(visible);
        LlmRequest hidden = new LlmRequest(UUID.randomUUID().toString(), other.getId(), otherApiKey.getId(), service, false);
        hidden.succeed(deployment.getId(), 100, 100, 50, 200, 0); requests.save(hidden);

        mvc.perform(get("/api/me/requests").header("Authorization", "Bearer " + key.secret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceKey").value("logical-analysis"))
                .andExpect(jsonPath("$[0].serviceDisplayName").value("Logical Analysis"))
                .andExpect(jsonPath("$[0].deploymentDisplayName").value("Production Model"))
                .andExpect(jsonPath("$[0].stream").value(true));

        mvc.perform(get("/api/me/usage").header("Authorization", "Bearer " + key.secret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestCount").value(1))
                .andExpect(jsonPath("$.inputTokens").value(3))
                .andExpect(jsonPath("$.outputTokens").value(2));
    }
}
