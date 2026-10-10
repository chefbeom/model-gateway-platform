package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.diagnostic.RequestDiagnosticService;
import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:project_local_only;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ProjectLocalOnlyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyService keys;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired ProjectServiceAccessRepository serviceAccess;
    @Autowired ExternalProviderRepository providers;
    @Autowired ProjectExternalAccessRepository externalAccess;
    @Autowired LlmRequestRepository requests;
    @Autowired SecretCipher cipher;
    @Autowired RequestDiagnosticService diagnostics;

    @Test
    void createPersistsBoundaryAndLegacyPatchDoesNotClearIt() throws Exception {
        Organization organization = organizations.save(new Organization("Protected project admin"));
        String created = mvc.perform(post("/api/admin/projects").header("X-Admin-Token", "integration-admin-token")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of(
                        "organizationId", organization.getId(), "name", "private", "externalAiBlocked", true))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode project = mapper.readTree(created);
        assertThat(project.path("externalAiBlocked").asBoolean()).isTrue();
        String id = project.path("id").asText();
        String patched = mvc.perform(patch("/api/admin/projects/" + id).header("X-Admin-Token", "integration-admin-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"renamed\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(patched).path("externalAiBlocked").asBoolean()).isTrue();
        assertThat(projects.findById(java.util.UUID.fromString(id)).orElseThrow().isExternalAiBlocked()).isTrue();
        mvc.perform(patch("/api/admin/projects/" + id).header("X-Admin-Token", "integration-admin-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"renamed\",\"externalAiBlocked\":false}"))
                .andExpect(status().isOk());
        assertThat(projects.findById(java.util.UUID.fromString(id)).orElseThrow().isExternalAiBlocked()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void externalOnlyServiceIsRejectedEvenWithApprovedExternalAccess(boolean stream) throws Exception {
        try (Fixture fixture = new Fixture(true, FailoverPolicy.COMPATIBLE)) {
            JsonNode response = fixture.request(stream, false, 503);
            assertThat(response.path("error").path("code").asText()).isEqualTo("LOCAL_MODEL_UNAVAILABLE");
            assertThat(response.path("error").path("message").asText()).contains("외부 AI 전송을 금지");
            assertThat(fixture.externalCalls).hasValue(0);
            LlmRequest trace = fixture.trace();
            assertThat(trace.getDataProtectionAction()).isEqualTo("LOCAL_ONLY");
            assertThat(trace.getDataExternalAllowed()).isFalse();
            var diagnostic = diagnostics.view(trace.getId()).orElseThrow();
            assertThat(diagnostic.targets().get(0).reasonCodes()).contains("PROJECT_EXTERNAL_AI_BLOCKED");
            assertThat(diagnostic.recommendations()).extracting(item -> item.code()).contains("LOCAL_MODEL_UNAVAILABLE");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanInputUsesLocalModelInsteadOfExternalStrictPrimaryForEveryApiKey(boolean stream) throws Exception {
        try (Fixture fixture = new Fixture(true, FailoverPolicy.STRICT)) {
            AtomicInteger local = fixture.addLocal(200, true, "[\"STREAMING\"]", true);
            fixture.request(stream, false, 200);
            fixture.secret = keys.issue(fixture.project.getId(), "second-key", null).secret();
            fixture.request(stream, false, 200);
            assertThat(local).hasValue(2);
            assertThat(fixture.externalCalls).hasValue(0);
            assertThat(fixture.trace().getDataProtectionMode()).isEqualTo("ENFORCE");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localFailureSwitchesToAnotherLocalTargetButNeverToExternal(boolean stream) throws Exception {
        try (Fixture fixture = new Fixture(true, FailoverPolicy.COMPATIBLE)) {
            AtomicInteger first = fixture.addLocal(503, true, "[\"STREAMING\"]", true);
            AtomicInteger second = fixture.addLocal(200, true, "[\"STREAMING\"]", true);
            fixture.request(stream, false, 200);
            assertThat(first).hasValue(1);
            assertThat(second).hasValue(1);
            assertThat(fixture.externalCalls).hasValue(0);
            assertThat(fixture.trace().getFailoverCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allLocalFailuresDoNotFallBackToHealthyExternalProvider(boolean stream) throws Exception {
        try (Fixture fixture = new Fixture(true, FailoverPolicy.COMPATIBLE)) {
            AtomicInteger local = fixture.addLocal(502, true, "[\"STREAMING\"]", true);
            fixture.request(stream, false, 503);
            assertThat(local).hasValue(1);
            assertThat(fixture.externalCalls).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localCapacityErrorIsPreservedWithoutExternalFallback(boolean stream) throws Exception {
        try (Fixture fixture = new Fixture(true, FailoverPolicy.COMPATIBLE)) {
            fixture.addLocal(503, true, "[\"STREAMING\"]", true);
            JsonNode response = fixture.request(stream, false, 429);
            assertThat(response.path("error").path("code").asText()).isEqualTo("MODEL_AT_CAPACITY");
            assertThat(fixture.externalCalls).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void visionRequestRejectsTextOnlyLocalModel(boolean stream) throws Exception {
        try (Fixture fixture = new Fixture(true, FailoverPolicy.COMPATIBLE)) {
            AtomicInteger local = fixture.addLocal(200, true, "[\"STREAMING\"]", true);
            fixture.request(stream, true, 503);
            assertThat(local).hasValue(0);
            assertThat(fixture.externalCalls).hasValue(0);
            assertThat(diagnostics.view(fixture.trace().getId()).orElseThrow().targets())
                    .anySatisfy(target -> assertThat(target.reasonCodes()).contains("CAPABILITY_MISSING"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unloadedAndUnrelatedLocalModelsAreNotUsed(boolean stream) throws Exception {
        try (Fixture fixture = new Fixture(true, FailoverPolicy.COMPATIBLE)) {
            AtomicInteger unloaded = fixture.addLocal(200, false, "[\"STREAMING\",\"VISION\"]", true);
            AtomicInteger unrelated = fixture.addLocal(200, true, "[\"STREAMING\",\"VISION\"]", false);
            fixture.request(stream, false, 503);
            assertThat(unloaded).hasValue(0);
            assertThat(unrelated).hasValue(0);
            assertThat(fixture.externalCalls).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrestrictedProjectStillCanUseApprovedExternalOnlyService(boolean stream) throws Exception {
        try (Fixture fixture = new Fixture(false, FailoverPolicy.COMPATIBLE)) {
            fixture.request(stream, false, 200);
            assertThat(fixture.externalCalls).hasValue(1);
        }
    }

    private final class Fixture implements AutoCloseable {
        final List<HttpServer> servers = new ArrayList<>();
        final AtomicInteger externalCalls = new AtomicInteger();
        final Organization organization;
        final Project project;
        final LlmService logical;
        String secret;
        int priority = 2;

        Fixture(boolean protectedProject, FailoverPolicy policy) throws Exception {
            organization = organizations.save(new Organization("Local boundary fixture"));
            Project newProject = new Project(organization.getId(), "client");
            newProject.configureExternalAiBlocked(protectedProject);
            project = projects.save(newProject);
            secret = keys.issue(project.getId(), "test", null).secret();
            logical = services.save(new LlmService(organization.getId(), "private-chat", "Private Chat", policy,
                    RetryPolicy.AGGRESSIVE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), logical.getId()));
            HttpServer server = server(200, externalCalls);
            ExternalProvider provider = new ExternalProvider(organization.getId(), ExternalProviderType.OPENAI,
                    "External", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", cipher.encrypt("test-provider-secret"));
            provider.recordHealth(true);
            provider = providers.save(provider);
            ModelDeployment deployment = deployments.save(ModelDeployment.external(provider.getId(), "external-gpt", "external-gpt",
                    "GPT", 8192, 4, "[\"STREAMING\",\"VISION\"]", BigDecimal.ZERO, BigDecimal.ZERO));
            targets.save(new ServiceTarget(logical.getId(), deployment.getId(), 1, 100, false, null));
            ProjectExternalAccess approval = new ProjectExternalAccess(project.getId(), provider.getId(), null, "integration");
            approval.decide(ExternalAccessStatus.APPROVED, true, true, null, null, null);
            externalAccess.save(approval);
        }

        AtomicInteger addLocal(int status, boolean loaded, String capabilities, boolean connectTarget) throws Exception {
            AtomicInteger calls = new AtomicInteger();
            HttpServer server = server(status, calls);
            InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "local-" + priority, null, "DIRECT", null));
            RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO,
                    "http://127.0.0.1:" + server.getAddress().getPort(), null);
            endpoint.recordHealth(true);
            endpoint = endpoints.save(endpoint);
            ModelDeployment model = deployments.save(new ModelDeployment(endpoint.getId(), "local-" + priority, "local-compatible",
                    "Local", null, null, 8192, loaded, 4, capabilities));
            if (connectTarget) targets.save(new ServiceTarget(logical.getId(), model.getId(), priority++, 100, false, null));
            return calls;
        }

        JsonNode request(boolean stream, boolean vision, int expectedStatus) throws Exception {
            String content = vision ? "[{\"type\":\"text\",\"text\":\"describe\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/photo.png\"}}]" : "\"hello\"";
            String response = mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + secret)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"private-chat\",\"stream\":" + stream
                            + ",\"messages\":[{\"role\":\"user\",\"content\":" + content + "}]}"))
                    .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
            if (stream && expectedStatus == 200) { assertThat(response).contains("data:", "[DONE]"); return mapper.createObjectNode(); }
            return mapper.readTree(response);
        }

        LlmRequest trace() { return requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0); }

        HttpServer server(int status, AtomicInteger calls) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
                boolean stream = body.path("stream").asBoolean();
                String response = status != 200 ? status == 503
                        ? "{\"error\":{\"message\":\"temporarily unavailable\"}}"
                        : "{\"error\":{\"message\":\"upstream transport failure\"}}"
                        : stream ? "data: {\"model\":\"physical\",\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}\n\ndata: [DONE]\n\n"
                        : "{\"model\":\"physical\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", stream && status == 200 ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            servers.add(server);
            return server;
        }

        @Override public void close() { servers.forEach(server -> server.stop(0)); }
    }
}
