package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.admin.AdminDtos;
import com.aiconnect.llmgateway.admin.ControlPlaneService;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.external.ProjectExternalAccessService;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:aiconnect_external_provider;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ExternalProviderScenarioIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyService apiKeys;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ExternalProviderRepository providers;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired ProjectServiceAccessRepository serviceAccess;
    @Autowired ProjectExternalAccessRepository externalAccess;
    @Autowired ProjectExternalAccessService externalPolicies;
    @Autowired LlmRequestRepository requests;
    @Autowired SecretCipher cipher;
    @Autowired ControlPlaneService controlPlane;

    @Test
    void requiresApprovalForManualUseAndExplicitToggleForAutomaticFailover() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer openAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        openAi.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"id\":\"chatcmpl-external\",\"model\":\"provider-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"external ok\"}}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        openAi.start();
        try {
            Organization organization = organizations.save(new Organization("External Scenario Org"));
            Project project = projects.save(new Project(organization.getId(), "external-client"));
            IssuedApiKey issued = apiKeys.issue(project.getId(), "scenario", null);

            ExternalProvider provider = new ExternalProvider(organization.getId(), ExternalProviderType.OPENAI,
                    "OpenAI Test", "http://127.0.0.1:" + openAi.getAddress().getPort() + "/v1", cipher.encrypt("provider-secret"));
            provider.recordHealth(true);
            provider = providers.save(provider);
            ModelDeployment cloud = deployments.save(ModelDeployment.external(provider.getId(), "provider-model",
                    "text-compatible", "External Model", 128000, 20,
                    "[\"CHAT_COMPLETION\",\"STRUCTURED_OUTPUT\"]", BigDecimal.valueOf(1000), BigDecimal.valueOf(2000)));

            LlmService manual = services.save(new LlmService(organization.getId(), "text-cloud", "Text Cloud",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            controlPlane.addTarget(manual.getId(), new AdminDtos.CreateTarget(cloud.getId(), 1, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), manual.getId()));

            perform(issued.secret(), "text-cloud").andExpect(status().isServiceUnavailable());
            assertThat(calls).hasValue(0);

            externalPolicies.decide(project.getId(), provider.getId(), ExternalAccessStatus.APPROVED,
                    true, false, BigDecimal.valueOf(100), null, null);
            ProjectExternalAccess approval = externalAccess.findByProjectIdAndProviderId(project.getId(), provider.getId()).orElseThrow();

            perform(issued.secret(), "text-cloud").andExpect(status().isOk())
                    .andExpect(jsonPath("$.model").value("text-cloud"))
                    .andExpect(jsonPath("$.choices[0].message.content").value("external ok"));
            assertThat(calls).hasValue(1);
            assertThat(authorization).hasValue("Bearer provider-secret");
            LlmRequest manualRequest = requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0);
            assertThat(manualRequest.getFinalProviderType()).isEqualTo("OPENAI");
            assertThat(manualRequest.getRoutingReason()).isEqualTo("MANUAL_EXTERNAL");
            assertThat(manualRequest.getEstimatedCost()).isEqualByComparingTo("0.008000");
            assertThat(manualRequest.getCostCurrency()).isEqualTo(Currency.KRW);
            assertThat(manualRequest.getInputUnitPrice()).isEqualByComparingTo("1000");
            assertThat(manualRequest.getOutputUnitPrice()).isEqualByComparingTo("2000");

            InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "offline-local", null, "DIRECT", null));
            RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://127.0.0.1:1", null);
            endpoint.recordHealth(false); endpoint.recordHealth(false); endpoint.recordHealth(false);
            endpoint = endpoints.save(endpoint);
            ModelDeployment local = deployments.save(new ModelDeployment(endpoint.getId(), "local-model", "text-compatible",
                    "Offline Local", null, null, 8192, true, 1, "[]"));
            LlmService resilient = services.save(new LlmService(organization.getId(), "text-resilient", "Text Resilient",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            controlPlane.addTarget(resilient.getId(), new AdminDtos.CreateTarget(local.getId(), 1, 100, false, null));
            controlPlane.addTarget(resilient.getId(), new AdminDtos.CreateTarget(cloud.getId(), 100, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), resilient.getId()));

            perform(issued.secret(), "text-resilient").andExpect(status().isServiceUnavailable());
            assertThat(calls).hasValue(1);

            approval.decide(ExternalAccessStatus.APPROVED, true, true, BigDecimal.valueOf(100), null, null);
            externalAccess.save(approval);
            perform(issued.secret(), "text-resilient").andExpect(status().isOk())
                    .andExpect(jsonPath("$.model").value("text-resilient"));
            assertThat(calls).hasValue(2);
            LlmRequest failoverRequest = requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0);
            assertThat(failoverRequest.getRoutingReason()).isEqualTo("AUTO_FAILOVER");
            assertThat(failoverRequest.getFailoverCount()).isEqualTo(1);
        } finally { openAi.stop(0); }
    }

    private org.springframework.test.web.servlet.ResultActions perform(String key, String model) throws Exception {
        return mvc.perform(post("/v1/chat/completions")
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":false}"));
    }
}
