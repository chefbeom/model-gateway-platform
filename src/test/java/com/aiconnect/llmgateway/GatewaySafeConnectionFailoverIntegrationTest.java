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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_safe_connection_failover;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class GatewaySafeConnectionFailoverIntegrationTest {
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
    @Autowired LlmRequestAttemptRepository attempts;

    @Test
    void safePolicyRetriesSecondaryWhenPrimaryConnectionIsRefused() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AtomicInteger secondaryCalls = new AtomicInteger();
        HttpServer secondary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secondary.createContext("/v1/chat/completions", exchange -> {
            secondaryCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"model\":\"physical-secondary\",\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        secondary.start();
        try {
            Organization organization = organizations.save(new Organization("SAFE connection failover"));
            Project project = projects.save(new Project(organization.getId(), "client"));
            IssuedApiKey key = apiKeyService.issue(project.getId(), "safe-connect", null);
            ModelDeployment primary = deployment(organization, "primary", closedPort, "shared-model");
            ModelDeployment fallback = deployment(organization, "secondary", secondary.getAddress().getPort(), "shared-model");
            LlmService service = services.save(new LlmService(organization.getId(), "safe-connect", "SAFE Connect",
                    FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]",
                    java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
            targets.save(new ServiceTarget(service.getId(), primary.getId(), 1, 100, false, null));
            targets.save(new ServiceTarget(service.getId(), fallback.getId(), 2, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), service.getId()));

            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"safe-connect\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.model").value("safe-connect"));

            LlmRequest recorded = requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0);
            assertThat(recorded.getFailoverCount()).isEqualTo(1);
            assertThat(recorded.getFinalDeploymentId()).isEqualTo(fallback.getId());
            assertThat(attempts.count()).isEqualTo(2);
            assertThat(secondaryCalls).hasValue(1);
        } finally {
            secondary.stop(0);
        }
    }

    private ModelDeployment deployment(Organization organization, String nodeName, int port, String compatibilityKey) {
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), nodeName, null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://127.0.0.1:" + port, null);
        endpoint.recordHealth(true);
        endpoint = endpoints.save(endpoint);
        return deployments.save(new ModelDeployment(endpoint.getId(), "physical-" + nodeName, compatibilityKey,
                nodeName, null, null, 8192, true, 2, "[\"CHAT_COMPLETION\"]"));
    }
}
