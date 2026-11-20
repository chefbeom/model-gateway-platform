package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.admin.AdminDtos;
import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.domain.ExternalAccessStatus;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.ExternalProviderType;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.ProjectExternalAccess;
import com.aiconnect.llmgateway.domain.ProjectServiceAccess;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.external.ExternalProviderAdministrationService;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectExternalAccessRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.ProjectServiceAccessRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.repository.ServiceTargetRepository;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.OpenAiRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.runtime.StreamingLmStudioRuntimeClient;
import com.aiconnect.llmgateway.runtime.StreamingOpenAiRuntimeClient;
import com.aiconnect.llmgateway.runtime.StreamingRuntimeResult;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:external_provider_ssrf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ExternalProviderSsrfIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyService apiKeys;
    @Autowired ExternalProviderRepository providers;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired ProjectServiceAccessRepository serviceAccess;
    @Autowired ProjectExternalAccessRepository externalAccess;
    @Autowired LlmRequestRepository requests;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired SecretCipher cipher;
    @Autowired ControlPlaneService controlPlane;
    @Autowired AuditService audit;
    @Autowired EntityManager entityManager;
    @Autowired GatewayProperties gatewayProperties;
    @Autowired @Qualifier("externalProviderRestClient") RestClient externalProviderRestClient;
    @Autowired InferenceRuntimeClient localRuntimeClient;
    @Autowired StreamingLmStudioRuntimeClient localStreamingClient;

    @Test
    void rejectsForbiddenExternalProviderUrlsOnCreateAndUpdate() throws Exception {
        Organization org = organizations.save(new Organization("SSRF Create/Update Org " + UUID.randomUUID()));

        for (String forbiddenUrl : List.of(
                "http://127.0.0.1:8080/v1",
                "http://localhost:8080/v1",
                "http://169.254.169.254/latest/meta-data",
                "http://10.0.0.5/v1",
                "http://100.64.0.10/v1",
                "http://[fd00::1]/v1",
                "http://redis:6379/v1",
                "http://metadata.google.internal/v1"
        )) {
            String createBody = objectMapper.writeValueAsString(java.util.Map.of(
                    "organizationId", org.getId(),
                    "displayName", "Unsafe " + UUID.randomUUID(),
                    "baseUrl", forbiddenUrl,
                    "apiKey", "secret-key"
            ));
            mvc.perform(post("/api/admin/external-providers")
                            .header("X-Admin-Token", "integration-admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
        }

        assertThat(providers.findByOrganizationIdOrderByDisplayNameAsc(org.getId())).isEmpty();

        ExternalProvider validProvider = providers.save(new ExternalProvider(org.getId(), ExternalProviderType.OPENAI,
                "Valid Provider", "https://api.openai.com/v1", cipher.encrypt("key-1")));

        String updateBody = objectMapper.writeValueAsString(java.util.Map.of(
                "baseUrl", "http://169.254.169.254/latest/meta-data",
                "apiKey", "key-2"
        ));
        mvc.perform(patch("/api/admin/external-providers/{providerId}", validProvider.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_URL_FORBIDDEN"));

        assertThat(providers.findById(validProvider.getId()).orElseThrow().getBaseUrl())
                .isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void blocksUnsafePersistedProviderBeforeConnectingOnProbeGatewayAndPlayground() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer internalTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        internalTarget.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] response = "{\"data\":[{\"id\":\"leaked\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        internalTarget.start();
        try {
            Organization org = organizations.save(new Organization("SSRF Call-Time Org " + UUID.randomUUID()));
            Project project = projects.save(new Project(org.getId(), "ssrf-client"));
            IssuedApiKey key = apiKeys.issue(project.getId(), "ssrf-key", null);

            ExternalProvider persistedUnsafe = new ExternalProvider(org.getId(), ExternalProviderType.OPENAI,
                    "Legacy Unsafe", "http://127.0.0.1:" + internalTarget.getAddress().getPort() + "/v1",
                    cipher.encrypt("provider-secret"));
            persistedUnsafe.recordHealth(true);
            persistedUnsafe = providers.save(persistedUnsafe);

            ModelDeployment deployment = deployments.save(ModelDeployment.external(
                    persistedUnsafe.getId(), "gpt-ssrf", "gpt-ssrf", "SSRF Model",
                    8192, 10, "[\"CHAT_COMPLETION\",\"STREAMING\"]", BigDecimal.ZERO, BigDecimal.ZERO));
            LlmService service = services.save(new LlmService(org.getId(), "ssrf-service", "SSRF Service",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            controlPlane.addTarget(service.getId(), new AdminDtos.CreateTarget(deployment.getId(), 1, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), service.getId()));
            ProjectExternalAccess approval = new ProjectExternalAccess(project.getId(), persistedUnsafe.getId(), null, "test");
            approval.decide(ExternalAccessStatus.APPROVED, true, true, null, null, null);
            externalAccess.save(approval);

            for (boolean stream : new boolean[]{false, true}) {
                String gatewayBody = "{\"model\":\"ssrf-service\",\"stream\":" + stream
                        + ",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
                mvc.perform(post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + key.secret())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(gatewayBody))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.code").value("EXTERNAL_PROVIDER_URL_FORBIDDEN"));

                String playgroundBody = "{\"targetId\":\"" + deployment.getId() + "\",\"stream\":" + stream
                        + ",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
                mvc.perform(post("/api/admin/organizations/{orgId}/playground/chat", org.getId())
                                .header("X-Admin-Token", "integration-admin-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(playgroundBody))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
            }

            mvc.perform(post("/api/admin/external-providers/{providerId}/probe", persistedUnsafe.getId())
                            .header("X-Admin-Token", "integration-admin-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_URL_FORBIDDEN"));

            assertThat(hits).hasValue(0);
        } finally {
            internalTarget.stop(0);
        }
    }

    @Test
    void blocksDnsRebindingBetweenRegistrationAndOutboundExecution() throws Exception {
        AtomicBoolean rebindToMetadata = new AtomicBoolean(false);
        ExternalUrlSafetyValidator rebindingValidator = new ExternalUrlSafetyValidator(false, host -> {
            if (!rebindToMetadata.get()) {
                return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 93, (byte) 184, (byte) 216, 34})};
            }
            return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254})};
        });

        OpenAiRuntimeClient rebindSyncClient = new OpenAiRuntimeClient(
                externalProviderRestClient, objectMapper, cipher, rebindingValidator);
        StreamingOpenAiRuntimeClient rebindStreamingClient = new StreamingOpenAiRuntimeClient(
                objectMapper, cipher, gatewayProperties, rebindingValidator);
        ExternalProviderAdministrationService adminService = new ExternalProviderAdministrationService(
                providers, deployments, targets, externalAccess, requests, organizations,
                cipher, rebindSyncClient, rebindingValidator, objectMapper, audit, entityManager);

        Organization org = organizations.save(new Organization("DNS Rebind Org " + UUID.randomUUID()));
        ExternalProviderAdministrationService.ProviderView created = adminService.create(
                org.getId(), "Rebinding Provider", "https://rebind.example.com/v1", "provider-secret");
        ExternalProvider entity = providers.findById(created.id()).orElseThrow();

        rebindToMetadata.set(true);

        ObjectNode request = objectMapper.createObjectNode().put("model", "gpt-test");
        request.putArray("messages").addObject().put("role", "user").put("content", "ping");

        assertThatThrownBy(() -> adminService.probe(entity.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
        assertThatThrownBy(() -> rebindSyncClient.chatCompletion(entity, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
        assertThatThrownBy(() -> rebindStreamingClient.chatCompletion(entity, request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
    }

    @Test
    void doesNotFollowHttpRedirectsOnSyncOrStreamingExternalClients() throws Exception {
        AtomicInteger secretTargetHits = new AtomicInteger();
        HttpServer secretTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secretTarget.createContext("/", exchange -> {
            secretTargetHits.incrementAndGet();
            byte[] response = "{\"data\":[{\"id\":\"internal-secret\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        secretTarget.start();

        HttpServer redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirector.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + secretTarget.getAddress().getPort() + "/latest/meta-data/");
            byte[] body = "<html>Redirecting</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(302, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        redirector.start();

        try {
            ExternalUrlSafetyValidator loopbackAllowedForRedirectTest = new ExternalUrlSafetyValidator(true);
            OpenAiRuntimeClient syncClient = new OpenAiRuntimeClient(
                    externalProviderRestClient, objectMapper, cipher, loopbackAllowedForRedirectTest);
            StreamingOpenAiRuntimeClient streamingClient = new StreamingOpenAiRuntimeClient(
                    objectMapper, cipher, gatewayProperties, loopbackAllowedForRedirectTest);
            ExternalProvider redirectProvider = new ExternalProvider(UUID.randomUUID(), ExternalProviderType.OPENAI,
                    "Redirector", "http://127.0.0.1:" + redirector.getAddress().getPort() + "/v1",
                    cipher.encrypt("provider-secret"));

            RuntimeResult modelsResult = syncClient.listModels(redirectProvider);
            assertThat(modelsResult.statusCode()).isEqualTo(302);
            assertThat(modelsResult.isSuccessful()).isFalse();

            ObjectNode chatBody = objectMapper.createObjectNode().put("model", "gpt-test");
            chatBody.putArray("messages").addObject().put("role", "user").put("content", "hi");

            RuntimeResult chatResult = syncClient.chatCompletion(redirectProvider, chatBody);
            assertThat(chatResult.statusCode()).isEqualTo(302);
            assertThat(chatResult.isSuccessful()).isFalse();

            StreamingRuntimeResult streamResult = streamingClient.chatCompletion(redirectProvider, chatBody);
            assertThat(streamResult.statusCode()).isEqualTo(302);
            streamResult.body().close();

            assertThat(secretTargetHits).hasValue(0);
        } finally {
            redirector.stop(0);
            secretTarget.stop(0);
        }
    }

    @Test
    void localRuntimeClientsRemainUnrestrictedForLoopbackAndPrivateNodes() throws Exception {
        AtomicInteger localHits = new AtomicInteger();
        HttpServer localServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        localServer.createContext("/v1/chat/completions", exchange -> {
            localHits.incrementAndGet();
            ObjectNode req = (ObjectNode) objectMapper.readTree(exchange.getRequestBody());
            boolean stream = req.path("stream").asBoolean(false);
            String response = stream
                    ? "data: {\"model\":\"local\",\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                    : "{\"model\":\"local\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        localServer.start();
        try {
            Organization org = organizations.save(new Organization("Local Runtime Org " + UUID.randomUUID()));
            InferenceNode node = nodes.save(new InferenceNode(org.getId(), "local-node", null, "DIRECT", null));
            RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO,
                    "http://127.0.0.1:" + localServer.getAddress().getPort(), null));

            ObjectNode syncReq = objectMapper.createObjectNode().put("model", "local").put("stream", false);
            syncReq.putArray("messages").addObject().put("role", "user").put("content", "hi");
            assertThat(localRuntimeClient.chatCompletion(endpoint, syncReq).statusCode()).isEqualTo(200);

            ObjectNode streamReq = objectMapper.createObjectNode().put("model", "local").put("stream", true);
            streamReq.putArray("messages").addObject().put("role", "user").put("content", "hi");
            StreamingRuntimeResult streamResult = localStreamingClient.chatCompletion(endpoint, streamReq);
            assertThat(streamResult.statusCode()).isEqualTo(200);
            streamResult.body().close();

            assertThat(localHits).hasValue(2);
        } finally {
            localServer.stop(0);
        }
    }
}
