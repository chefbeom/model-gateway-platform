package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_organization_overview;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class OrganizationOverviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired LlmServiceRepository services;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired LlmRequestRepository requests;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired IncidentRepository incidents;

    @Test
    void aggregatesOnlyTheSelectedOrganizationIncludingP95FailoversAndIncidents() throws Exception {
        Organization first = organizations.save(new Organization("Overview First"));
        Organization second = organizations.save(new Organization("Overview Second"));
        Project firstProject = projects.save(new Project(first.getId(), "First Project"));
        Project secondProject = projects.save(new Project(second.getId(), "Second Project"));
        LlmService firstService = services.save(new LlmService(first.getId(), "first-service", "First Service",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.valueOf(1000), BigDecimal.valueOf(2000)));
        LlmService secondService = services.save(new LlmService(second.getId(), "second-service", "Second Service",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        ApiKey firstKey = apiKeys.save(new ApiKey(firstProject.getId(), "first-key", "sk_llmg_overview_first", "a".repeat(64), null));
        ApiKey secondKey = apiKeys.save(new ApiKey(secondProject.getId(), "second-key", "sk_llmg_overview_second", "b".repeat(64), null));

        LlmRequest successful = new LlmRequest("overview-success", firstProject.getId(), firstKey.getId(), firstService, false);
        successful.succeed(null, 10, 5, 100, 200, 0);
        requests.save(successful);
        LlmRequest failed = new LlmRequest("overview-failed", firstProject.getId(), firstKey.getId(), firstService, false);
        failed.fail("UPSTREAM_FAILURE", 502, 900, 2);
        requests.save(failed);
        requests.save(new LlmRequest("overview-active", firstProject.getId(), firstKey.getId(), firstService, true));
        LlmRequest other = new LlmRequest("overview-other-org", secondProject.getId(), secondKey.getId(), secondService, false);
        other.succeed(null, 999, 999, 500, 200, 7);
        requests.save(other);

        InferenceNode node = nodes.save(new InferenceNode(first.getId(), "overview-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://overview-runtime:1234", null);
        endpoint.recordHealth(false);
        endpoint.recordHealth(false);
        endpoint.recordHealth(false);
        endpoints.save(endpoint);
        incidents.save(new Incident(endpoint.getId(), "Synthetic overview incident"));

        mvc.perform(get("/api/admin/organizations/{organizationId}/overview", first.getId())
                        .header("X-Admin-Token", "integration-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests24h").value(3))
                .andExpect(jsonPath("$.succeeded24h").value(1))
                .andExpect(jsonPath("$.failed24h").value(1))
                .andExpect(jsonPath("$.activeRequests").value(1))
                .andExpect(jsonPath("$.successRate24h").value(0.5))
                .andExpect(jsonPath("$.errorRate24h").value(0.5))
                .andExpect(jsonPath("$.inputTokens24h").value(10))
                .andExpect(jsonPath("$.outputTokens24h").value(5))
                .andExpect(jsonPath("$.p95LatencyMs24h").value(900))
                .andExpect(jsonPath("$.failovers24h").value(2))
                .andExpect(jsonPath("$.endpoints").value(1))
                .andExpect(jsonPath("$.unhealthyEndpoints").value(1))
                .andExpect(jsonPath("$.openIncidents").value(1));
    }
}
