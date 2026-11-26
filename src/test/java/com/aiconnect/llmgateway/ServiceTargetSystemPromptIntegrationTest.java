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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_target_prompt;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ServiceTargetSystemPromptIntegrationTest {
    private static final String ADMIN = "integration-admin-token";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyService apiKeyService;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired com.aiconnect.llmgateway.security.RuntimeTrustAttestationRepository runtimeTrustFixture;
    @Autowired com.aiconnect.llmgateway.security.ProjectRuntimeApprovalRepository runtimeApprovalFixture;
    @Autowired ProjectRepository runtimeProjectsFixture;
    @Autowired InferenceNodeRepository runtimeNodesFixture;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired ProjectServiceAccessRepository access;
    @Autowired LlmRequestAttemptRepository attemptRepository;

    private ModelDeployment deployment(Organization org, String name, int port) {
        InferenceNode node = nodes.save(new InferenceNode(org.getId(), name + "-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://127.0.0.1:" + port, null);
        endpoint.recordHealth(true);
        endpoint = RuntimeTestTrust.internal(endpoints, runtimeTrustFixture, runtimeApprovalFixture, runtimeProjectsFixture, runtimeNodesFixture, endpoint);
        return deployments.save(new ModelDeployment(endpoint.getId(), name, name, null, null, 8192, true, 4, "[]"));
    }

    private LlmService service(Organization org, String key, String prompt) {
        LlmService s = new LlmService(org.getId(), key, key, FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]",
                BigDecimal.ZERO, BigDecimal.ZERO);
        if (prompt != null) s.configureSystemPromptTemplate(prompt, false);
        return services.save(s);
    }

    private int version(UUID serviceId) { return services.findById(serviceId).orElseThrow().getPromptVersion(); }

    private String targetUrl(UUID serviceId) { return "/api/admin/services/" + serviceId + "/targets"; }

    private void patchTarget(UUID serviceId, UUID targetId, String json) throws Exception {
        mvc.perform(patch(targetUrl(serviceId) + "/" + targetId).header("X-Admin-Token", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    @Test
    void patchTrimsClearsAndBumpsPromptVersionOnlyOnChange() throws Exception {
        Organization org = organizations.save(new Organization("Target Prompt Org 1"));
        LlmService svc = service(org, "tp-patch", null);
        ServiceTarget target = targets.save(new ServiceTarget(svc.getId(), deployment(org, "tp-patch-dep", 1).getId(), 1, 100, false, null));
        int v0 = version(svc.getId());

        patchTarget(svc.getId(), target.getId(), "{\"systemPromptTemplate\":\"  Be brief  \"}");
        mvc.perform(get(targetUrl(svc.getId())).header("X-Admin-Token", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].systemPromptTemplate").value("Be brief"));
        assertThat(version(svc.getId())).isEqualTo(v0 + 1);

        patchTarget(svc.getId(), target.getId(), "{\"systemPromptTemplate\":\"Be brief\"}");
        assertThat(version(svc.getId())).isEqualTo(v0 + 1);
        patchTarget(svc.getId(), target.getId(), "{\"priority\":2}");
        assertThat(version(svc.getId())).isEqualTo(v0 + 1);

        patchTarget(svc.getId(), target.getId(), "{\"clearSystemPromptTemplate\":true}");
        assertThat(targets.findById(target.getId()).orElseThrow().getSystemPromptTemplate()).isNull();
        assertThat(version(svc.getId())).isEqualTo(v0 + 2);

        patchTarget(svc.getId(), target.getId(), "{\"systemPromptTemplate\":\"x\"}");
        assertThat(version(svc.getId())).isEqualTo(v0 + 3);
        patchTarget(svc.getId(), target.getId(), "{\"systemPromptTemplate\":\"   \"}");
        assertThat(targets.findById(target.getId()).orElseThrow().getSystemPromptTemplate()).isNull();
        assertThat(version(svc.getId())).isEqualTo(v0 + 4);

        mvc.perform(patch(targetUrl(svc.getId()) + "/" + target.getId()).header("X-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemPromptTemplate\":\"" + "a".repeat(16001) + "\"}"))
                .andExpect(status().isBadRequest());
        patchTarget(svc.getId(), target.getId(), "{\"systemPromptTemplate\":\"" + "a".repeat(16000) + "\"}");
    }

    @Test
    void createStoresPromptAndDeleteBumpsVersion() throws Exception {
        Organization org = organizations.save(new Organization("Target Prompt Org 2"));
        LlmService svc = service(org, "tp-create", null);
        ModelDeployment d1 = deployment(org, "tp-create-1", 1), d2 = deployment(org, "tp-create-2", 2);
        int v0 = version(svc.getId());

        String plain = mvc.perform(post(targetUrl(svc.getId())).header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deploymentId\":\"" + d1.getId() + "\",\"priority\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(version(svc.getId())).isEqualTo(v0);

        String withPrompt = mvc.perform(post(targetUrl(svc.getId())).header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deploymentId\":\"" + d2.getId() + "\",\"priority\":2,\"systemPromptTemplate\":\" Hello \"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(version(svc.getId())).isEqualTo(v0 + 1);
        UUID withId = UUID.fromString(objectMapper.readTree(withPrompt).path("id").asText());
        UUID plainId = UUID.fromString(objectMapper.readTree(plain).path("id").asText());
        assertThat(targets.findById(withId).orElseThrow().getSystemPromptTemplate()).isEqualTo("Hello");

        mvc.perform(delete(targetUrl(svc.getId()) + "/" + plainId).header("X-Admin-Token", ADMIN)).andExpect(status().is2xxSuccessful());
        assertThat(version(svc.getId())).isEqualTo(v0 + 1);
        mvc.perform(delete(targetUrl(svc.getId()) + "/" + withId).header("X-Admin-Token", ADMIN)).andExpect(status().is2xxSuccessful());
        assertThat(version(svc.getId())).isEqualTo(v0 + 2);
    }

    @Test
    void targetListAlwaysIncludesPromptKeyEvenWhenNull() throws Exception {
        Organization org = organizations.save(new Organization("Target Prompt Org 3"));
        LlmService svc = service(org, "tp-key", null);
        targets.save(new ServiceTarget(svc.getId(), deployment(org, "tp-key-dep", 1).getId(), 1, 100, false, null));
        String json = mvc.perform(get(targetUrl(svc.getId())).header("X-Admin-Token", ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json).contains("\"systemPromptTemplate\":null");
        assertThat(objectMapper.readTree(json).get(0).has("systemPromptTemplate")).isTrue();
        // the global non_null default still omits other null fields
        assertThat(objectMapper.readTree(json).get(0).has("maxConcurrencyOverride")).isFalse();
    }

    @Test
    void gatewayComposesServiceTargetAndClientPrompts() throws Exception {
        assertUpstreamSystemContent("S", "T", "S\n\nT\n\nC", false);
        assertUpstreamSystemContent("S", null, "S\n\nC", false);
        assertUpstreamSystemContent(null, "T", "T\n\nC", false);
        assertUpstreamSystemContent("S", "T", "S\n\nT\n\nC", true);
    }

    @Test
    void targetPromptDoesNotLeakIntoNextFailoverAttempt() throws Exception {
        AtomicInteger firstCalls = new AtomicInteger(), secondCalls = new AtomicInteger();
        AtomicReference<JsonNode> firstBody = new AtomicReference<>(), secondBody = new AtomicReference<>();
        HttpServer first = server(503, "{\"error\":{\"message\":\"down\"}}", "application/json", firstCalls, firstBody);
        HttpServer second = server(200, okJson(), "application/json", secondCalls, secondBody);
        try {
            Organization org = organizations.save(new Organization("Target Prompt Org 4"));
            Project project = projects.save(new Project(org.getId(), "tp-failover"));
            IssuedApiKey key = apiKeyService.issue(project.getId(), "tp", null);
            LlmService svc = new LlmService(org.getId(), "tp-failover", "tp-failover", FailoverPolicy.COMPATIBLE,
                    RetryPolicy.AGGRESSIVE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
            svc.configureSystemPromptTemplate("S", false);
            svc = services.save(svc);
            ServiceTarget t1 = new ServiceTarget(svc.getId(), deployment(org, "tp-f1", first.getAddress().getPort()).getId(), 1, 100, false, null);
            t1.configureSystemPromptTemplate("T1", false);
            targets.save(t1);
            targets.save(new ServiceTarget(svc.getId(), deployment(org, "tp-f2", second.getAddress().getPort()).getId(), 2, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), svc.getId()));

            mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"tp-failover\",\"messages\":[{\"role\":\"system\",\"content\":\"C\"},{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}"))
                    .andExpect(status().isOk());
            assertThat(firstCalls).hasValue(1);
            assertThat(secondCalls).hasValue(1);
            assertThat(firstBody.get().path("messages").get(0).path("content").asText()).isEqualTo("S\n\nT1\n\nC");
            assertThat(secondBody.get().path("messages").get(0).path("content").asText()).isEqualTo("S\n\nC");
        } finally {
            first.stop(0); second.stop(0);
        }
    }

    @Test
    void responseCacheHitSkipsLeaseAndLeavesNoAttemptRow() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonNode> received = new AtomicReference<>();
        HttpServer provider = server(200, okJson(), "application/json", calls, received);
        try {
            Organization org = organizations.save(new Organization("Target Prompt Cache Org"));
            Project project = projects.save(new Project(org.getId(), "tp-cache"));
            IssuedApiKey key = apiKeyService.issue(project.getId(), "tp", null);
            LlmService svc = new LlmService(org.getId(), "tp-cache", "tp-cache", FailoverPolicy.STRICT, RetryPolicy.SAFE,
                    false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
            svc.configureResponseCache(true, 300);
            svc = services.save(svc);
            UUID deploymentId = deployment(org, "tp-cache-dep", provider.getAddress().getPort()).getId();
            targets.save(new ServiceTarget(svc.getId(), deploymentId, 1, 100, false, null));
            access.save(new ProjectServiceAccess(project.getId(), svc.getId()));

            String body = "{\"model\":\"tp-cache\",\"messages\":[{\"role\":\"user\",\"content\":\"cache me\"}],\"stream\":false}";
            for (int i = 0; i < 2; i++) {
                mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + key.secret())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.model").value("tp-cache"));
            }
            assertThat(calls).hasValue(1);
            var rows = attemptRepository.findAll().stream()
                    .filter(a -> deploymentId.equals(ReflectionTestUtils.getField(a, "deploymentId")))
                    .toList();
            assertThat(rows).hasSize(1);
            assertThat(rows).noneMatch(a -> "IN_PROGRESS".equals(ReflectionTestUtils.getField(a, "status")));
        } finally {
            provider.stop(0);
        }
    }

    private void assertUpstreamSystemContent(String servicePrompt, String targetPrompt, String expected, boolean stream) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonNode> received = new AtomicReference<>();
        String sse = "data: {\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\n"
                + "data: {\"id\":\"c2\",\"model\":\"m\",\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n"
                + "data: [DONE]\n\n";
        HttpServer provider = stream ? server(200, sse, "text/event-stream", calls, received)
                : server(200, okJson(), "application/json", calls, received);
        try {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Organization org = organizations.save(new Organization("Target Prompt Gw " + suffix));
            Project project = projects.save(new Project(org.getId(), "tp-gw-" + suffix));
            IssuedApiKey key = apiKeyService.issue(project.getId(), "tp", null);
            LlmService svc = service(org, "tp-gw-" + suffix, servicePrompt);
            ServiceTarget target = new ServiceTarget(svc.getId(), deployment(org, "tp-gw-dep-" + suffix, provider.getAddress().getPort()).getId(), 1, 100, false, null);
            target.configureSystemPromptTemplate(targetPrompt, false);
            targets.save(target);
            access.save(new ProjectServiceAccess(project.getId(), svc.getId()));

            mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"tp-gw-" + suffix + "\",\"messages\":[{\"role\":\"system\",\"content\":\"C\"},{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":" + stream + "}"))
                    .andExpect(status().isOk());
            assertThat(calls).hasValue(1);
            JsonNode messages = received.get().path("messages");
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).path("content").asText()).isEqualTo(expected);
            assertThat(messages.get(1).path("content").asText()).isEqualTo("hi");
        } finally {
            provider.stop(0);
        }
    }

    private static String okJson() {
        return "{\"id\":\"chatcmpl-tp\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
    }

    private HttpServer server(int status, String response, String contentType, AtomicInteger calls, AtomicReference<JsonNode> received) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            received.set(objectMapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
