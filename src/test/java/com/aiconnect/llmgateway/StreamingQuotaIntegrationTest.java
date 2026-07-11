package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.quota.ProjectQuota;
import com.aiconnect.llmgateway.quota.ProjectQuotaRepository;
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
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_stream_quota;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class StreamingQuotaIntegrationTest {
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
    @Autowired ProjectQuotaRepository quotas;

    @Test
    void rateLimitRunsBeforeStreamingGatewayAndPreventsSecondRuntimeCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer runtime = runtime(calls);
        try {
            Organization organization = organizations.save(new Organization("Stream Quota Org"));
            Project project = projects.save(new Project(organization.getId(), "client"));
            IssuedApiKey key = apiKeyService.issue(project.getId(), "limited", null);
            quotas.save(new ProjectQuota(project.getId(), 1, null));
            InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "quota-runtime", null, "DIRECT", null));
            RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://127.0.0.1:" + runtime.getAddress().getPort(), null);
            endpoint.recordHealth(true); endpoint = endpoints.save(endpoint);
            ModelDeployment deployment = deployments.save(new ModelDeployment(endpoint.getId(), "quota-model", "Quota", null, null, 8192, true, 1, "[]"));
            LlmService service = services.save(new LlmService(organization.getId(), "quota-stream", "Quota Stream",
                    FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
            targets.save(new ServiceTarget(service.getId(), deployment.getId(), 1, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), service.getId()));
            String request = "{\"model\":\"quota-stream\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":true}";

            mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isOk());
            mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"));

            assertThat(calls).hasValue(1);
        } finally {
            runtime.stop(0);
        }
    }

    private HttpServer runtime(AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body = ("data: {\"model\":\"quota-model\",\"choices\":[]}\n\n"
                    + "data: {\"model\":\"quota-model\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); return server;
    }
}
