package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_failover;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class GatewayFailoverIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyService apiKeyService;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired ProjectServiceAccessRepository access;
    @Autowired LlmRequestRepository requests;

    @Test
    void aggressiveCompatiblePolicyRetriesSecondaryAndPersistsUsage() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger secondaryCalls = new AtomicInteger();
        HttpServer primary = server(503, "{\"error\":{\"message\":\"unavailable\"}}", primaryCalls);
        HttpServer secondary = server(200, "{\"id\":\"chatcmpl-test\",\"model\":\"physical-secondary\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}", secondaryCalls);
        try {
            Organization organization = organizations.save(new Organization("Failover Org"));
            Project project = projects.save(new Project(organization.getId(), "client"));
            IssuedApiKey issued = apiKeyService.issue(project.getId(), "test", null);
            InferenceNode primaryNode = nodes.save(new InferenceNode(organization.getId(), "primary", null, "DIRECT", null));
            InferenceNode secondaryNode = nodes.save(new InferenceNode(organization.getId(), "secondary", null, "DIRECT", null));
            RuntimeEndpoint primaryEndpoint = healthyEndpoint(primaryNode.getId(), primary.getAddress().getPort());
            RuntimeEndpoint secondaryEndpoint = healthyEndpoint(secondaryNode.getId(), secondary.getAddress().getPort());
            ModelDeployment primaryDeployment = deployments.save(new ModelDeployment(primaryEndpoint.getId(), "physical-primary", "Primary", null, null, 8192, true, 4, "[]"));
            ModelDeployment secondaryDeployment = deployments.save(new ModelDeployment(secondaryEndpoint.getId(), "physical-secondary", "Secondary", null, null, 8192, true, 4, "[]"));
            LlmService logical = services.save(new LlmService(organization.getId(), "text-pro", "Text Pro",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.AGGRESSIVE, false, "[]",
                    java.math.BigDecimal.valueOf(100), java.math.BigDecimal.valueOf(200)));
            targets.save(new ServiceTarget(logical.getId(), primaryDeployment.getId(), 1, 100, false, null));
            targets.save(new ServiceTarget(logical.getId(), secondaryDeployment.getId(), 2, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), logical.getId()));

            String response = mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + issued.secret()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"text-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":false}"))
                    .andExpect(status().isOk()).andExpect(header().exists("X-Request-Id")).andReturn().getResponse().getContentAsString();

            JsonNode result = objectMapper.readTree(response);
            assertThat(result.path("model").asText()).isEqualTo("text-pro");
            assertThat(primaryCalls).hasValue(1);
            assertThat(secondaryCalls).hasValue(1);
            LlmRequest recorded = requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0);
            assertThat(recorded.getFailoverCount()).isEqualTo(1);
            assertThat(recorded.getInputTokens()).isEqualTo(3);
            assertThat(recorded.getOutputTokens()).isEqualTo(2);
            RuntimeEndpoint failedPrimary = endpoints.findById(primaryEndpoint.getId()).orElseThrow();
            assertThat(failedPrimary.getHealthStatus()).isEqualTo(HealthStatus.SUSPECT);
            assertThat(failedPrimary.getConsecutiveFailures()).isEqualTo(1);
        } finally {
            primary.stop(0); secondary.stop(0);
        }
    }

    @Test
    void logicalServiceFixedTemperatureOverridesActualProjectRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonNode> received = new AtomicReference<>();
        HttpServer provider = server(200, "{\"id\":\"chatcmpl-temperature\",\"model\":\"physical-temperature\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}", calls, received);
        try {
            Organization organization = organizations.save(new Organization("Temperature Policy Org"));
            Project project = projects.save(new Project(organization.getId(), "temperature-client"));
            IssuedApiKey issued = apiKeyService.issue(project.getId(), "temperature-test", null);
            InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "temperature-node", null, "DIRECT", null));
            RuntimeEndpoint endpoint = healthyEndpoint(node.getId(), provider.getAddress().getPort());
            ModelDeployment deployment = deployments.save(new ModelDeployment(endpoint.getId(), "physical-temperature", "Temperature Target", null, null, 8192, true, 4, "[]"));
            LlmService logical = new LlmService(organization.getId(), "temperature-service", "Temperature Service",
                    FailoverPolicy.STRICT, false, "[]", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
            logical.configureTemperaturePolicy(TemperaturePolicy.FIXED, java.math.BigDecimal.ONE);
            logical = services.save(logical);
            targets.save(new ServiceTarget(logical.getId(), deployment.getId(), 1, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), logical.getId()));

            mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + issued.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"temperature-service\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"temperature\":0.2,\"stream\":false}"))
                    .andExpect(status().isOk());

            assertThat(calls).hasValue(1);
            assertThat(received.get().path("model").asText()).isEqualTo("physical-temperature");
            assertThat(received.get().path("temperature").asDouble()).isEqualTo(1.0);
        } finally {
            provider.stop(0);
        }
    }

    private RuntimeEndpoint healthyEndpoint(UUID nodeId, int port) {
        RuntimeEndpoint endpoint = new RuntimeEndpoint(nodeId, RuntimeType.LM_STUDIO, "http://127.0.0.1:" + port, null);
        endpoint.recordHealth(true);
        return endpoints.save(endpoint);
    }
    private HttpServer server(int status, String response, AtomicInteger calls) throws IOException {
        return server(status, response, calls, null);
    }
    private HttpServer server(int status, String response, AtomicInteger calls, AtomicReference<JsonNode> received) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (received != null) received.set(objectMapper.readTree(request));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
