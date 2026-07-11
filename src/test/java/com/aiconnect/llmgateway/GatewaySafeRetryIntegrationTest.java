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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_safe_retry;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class GatewaySafeRetryIntegrationTest {
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
    void safePolicyDoesNotReplayRequestAfterRuntimeReturned503() throws Exception {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger secondaryCalls = new AtomicInteger();
        HttpServer primary = server(503, primaryCalls);
        HttpServer secondary = server(200, secondaryCalls);
        try {
            Organization organization = organizations.save(new Organization("Safe Retry Org"));
            Project project = projects.save(new Project(organization.getId(), "safe-client"));
            IssuedApiKey issued = apiKeyService.issue(project.getId(), "safe", null);
            ModelDeployment first = deployment(organization, "safe-primary", primary.getAddress().getPort());
            ModelDeployment second = deployment(organization, "safe-secondary", secondary.getAddress().getPort());
            LlmService service = services.save(new LlmService(organization.getId(), "safe-service", "Safe Service",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]",
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
            targets.save(new ServiceTarget(service.getId(), first.getId(), 1, 100, false, null));
            targets.save(new ServiceTarget(service.getId(), second.getId(), 2, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), service.getId()));

            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + issued.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"safe-service\",\"messages\":[{\"role\":\"user\",\"content\":\"do once\"}]}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("UPSTREAM_REJECTED"));

            assertThat(primaryCalls).hasValue(1);
            assertThat(secondaryCalls).hasValue(0);
            assertThat(requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0).getFailoverCount()).isZero();
        } finally {
            primary.stop(0);
            secondary.stop(0);
        }
    }

    private ModelDeployment deployment(Organization organization, String model, int port) {
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), model, null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://127.0.0.1:" + port, null);
        endpoint.recordHealth(true);
        endpoint = endpoints.save(endpoint);
        return deployments.save(new ModelDeployment(endpoint.getId(), model, model, null, null, 8192, true, 4, "[]"));
    }

    private HttpServer server(int status, AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body = (status == 200
                    ? "{\"model\":\"secondary\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"
                    : "{\"error\":{\"message\":\"unavailable\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        return server;
    }
}
