package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.monitoring.RequestAttemptQueryRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_stream_interruption;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class GatewayStreamingInterruptionIntegrationTest {
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
    @Autowired RequestAttemptQueryRepository attemptQueries;

    @Test
    void recordsStartedStreamAsInterruptedWhenUpstreamBodyBreaks() throws Exception {
        HttpServer runtime = interruptedServer();
        try {
            Organization organization = organizations.save(new Organization("Interrupted Stream Org"));
            Project project = projects.save(new Project(organization.getId(), "client"));
            IssuedApiKey key = apiKeyService.issue(project.getId(), "stream", null);
            InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "broken-runtime", null, "DIRECT", null));
            RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://127.0.0.1:" + runtime.getAddress().getPort(), null);
            endpoint.recordHealth(true); endpoint = endpoints.save(endpoint);
            ModelDeployment deployment = deployments.save(new ModelDeployment(endpoint.getId(), "broken-model", "Broken", null, null, 8192, true, 1, "[]"));
            LlmService service = services.save(new LlmService(organization.getId(), "broken-stream", "Broken Stream",
                    FailoverPolicy.STRICT, RetryPolicy.AGGRESSIVE, false, "[]", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
            targets.save(new ServiceTarget(service.getId(), deployment.getId(), 1, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), service.getId()));

            Throwable thrown = catchThrowable(() -> mvc.perform(post("/v1/chat/completions")
                    .header("Authorization", "Bearer " + key.secret())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"model\":\"broken-stream\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":true}")));
            assertThat(thrown).isNotNull();

            LlmRequest recorded = requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0);
            assertThat(recorded.getStatus()).isEqualTo(RequestStatus.FAILED);
            assertThat(recorded.getErrorCode()).isEqualTo("STREAM_INTERRUPTED");
            RequestAttemptQueryRepository.AttemptProjection attempt = attemptQueries.findAttempts(recorded.getId()).get(0);
            assertThat(attempt.getErrorType()).isEqualTo("STREAM_INTERRUPTED");
            assertThat(attempt.isResponseStarted()).isTrue();
        } finally {
            runtime.stop(0);
        }
    }

    private HttpServer interruptedServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] partial = "data: {\"model\":\"broken-model\",\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, partial.length + 256L);
            exchange.getResponseBody().write(partial);
            exchange.close();
        });
        server.start(); return server;
    }
}
