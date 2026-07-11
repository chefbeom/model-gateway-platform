package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.sun.net.httpserver.HttpExchange;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_stream_failover;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class GatewayStreamingFailoverIntegrationTest {
    @Autowired MockMvc mvc;
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
    void aggressiveCompatiblePolicyFailsOverBeforeStreamAndHidesPhysicalModelNames() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger(); AtomicInteger secondaryCalls = new AtomicInteger();
        HttpServer primary = server(503, "{\"error\":{\"message\":\"unavailable\"}}", "application/json", primaryCalls);
        String sse = "data: {\"id\":\"chunk-1\",\"model\":\"physical-secondary\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\n"
                + "data: {\"id\":\"chunk-2\",\"model\":\"physical-secondary\",\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":3}}\n\n"
                + "data: [DONE]\n\n";
        HttpServer secondary = server(200, sse, "text/event-stream", secondaryCalls);
        try {
            Organization organization = organizations.save(new Organization("Stream Failover Org"));
            Project project = projects.save(new Project(organization.getId(), "stream-client"));
            IssuedApiKey issued = apiKeyService.issue(project.getId(), "stream-test", null);
            RuntimeEndpoint primaryEndpoint = endpoint(organization, "stream-primary", primary.getAddress().getPort());
            RuntimeEndpoint secondaryEndpoint = endpoint(organization, "stream-secondary", secondary.getAddress().getPort());
            ModelDeployment first = deployments.save(new ModelDeployment(primaryEndpoint.getId(), "physical-primary", "Primary", null, null, 8192, true, 4, "[]"));
            ModelDeployment second = deployments.save(new ModelDeployment(secondaryEndpoint.getId(), "physical-secondary", "Secondary", null, null, 8192, true, 4, "[]"));
            LlmService logical = services.save(new LlmService(organization.getId(), "stream-service", "Stream Service",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.AGGRESSIVE, false, "[]",
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
            targets.save(new ServiceTarget(logical.getId(), first.getId(), 1, 100, false, null));
            targets.save(new ServiceTarget(logical.getId(), second.getId(), 2, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), logical.getId()));

            String response = mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + issued.secret()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"stream-service\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":true}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(response).contains("\"model\":\"stream-service\"").doesNotContain("physical-secondary");
            assertThat(primaryCalls).hasValue(1); assertThat(secondaryCalls).hasValue(1);
            LlmRequest recorded = requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0);
            assertThat(recorded.getFailoverCount()).isEqualTo(1);
            assertThat(recorded.getInputTokens()).isEqualTo(4);
            assertThat(recorded.getOutputTokens()).isEqualTo(3);
        } finally { primary.stop(0); secondary.stop(0); }
    }
    private RuntimeEndpoint endpoint(Organization organization, String name, int port) {
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), name, null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://127.0.0.1:" + port, null); endpoint.recordHealth(true); return endpoints.save(endpoint);
    }
    private HttpServer server(int status, String response, String contentType, AtomicInteger calls) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, status, response, contentType, calls)); server.start(); return server;
    }
    private void respond(HttpExchange exchange, int status, String response, String contentType, AtomicInteger calls) throws IOException {
        calls.incrementAndGet(); exchange.getRequestBody().readAllBytes(); byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType); exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); exchange.close();
    }
}
