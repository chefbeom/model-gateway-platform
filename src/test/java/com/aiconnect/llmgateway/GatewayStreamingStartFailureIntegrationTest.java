package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_stream_start_failover;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class GatewayStreamingStartFailureIntegrationTest {
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
    void aggressivePolicyRetriesWhenSuccessfulHeadersHaveNoStreamBody() throws Exception {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        HttpServer first = emptyStreamServer(firstCalls);
        HttpServer second = sseServer(secondCalls);
        try {
            Organization organization = organizations.save(new Organization("Stream Start Org"));
            Project project = projects.save(new Project(organization.getId(), "stream-start-client"));
            IssuedApiKey key = apiKeyService.issue(project.getId(), "stream-start", null);
            ModelDeployment primary = deployment(organization, "empty-primary", first.getAddress().getPort());
            ModelDeployment secondary = deployment(organization, "working-secondary", second.getAddress().getPort());
            LlmService service = services.save(new LlmService(organization.getId(), "stream-start", "Stream Start",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.AGGRESSIVE, false, "[]",
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
            targets.save(new ServiceTarget(service.getId(), primary.getId(), 1, 100, false, null));
            targets.save(new ServiceTarget(service.getId(), secondary.getId(), 2, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), service.getId()));

            String body = mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"stream-start\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":true}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(firstCalls).hasValue(1);
            assertThat(secondCalls).hasValue(1);
            assertThat(body).contains("data:").contains("\"model\":\"stream-start\"");
            assertThat(requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0).getFailoverCount()).isEqualTo(1);
        } finally {
            first.stop(0); second.stop(0);
        }
    }

    private ModelDeployment deployment(Organization organization, String model, int port) {
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), model, null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://127.0.0.1:" + port, null);
        endpoint.recordHealth(true);
        endpoint = endpoints.save(endpoint);
        return deployments.save(new ModelDeployment(endpoint.getId(), model, model, null, null, 8192, true, 2, "[]"));
    }

    private HttpServer emptyStreamServer(AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start(); return server;
    }

    private HttpServer sseServer(AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body = ("data: {\"model\":\"working-secondary\",\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: {\"model\":\"working-secondary\",\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); return server;
    }
}
