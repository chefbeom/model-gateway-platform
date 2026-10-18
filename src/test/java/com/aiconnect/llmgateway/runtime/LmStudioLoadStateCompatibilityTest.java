package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.admin.DiscoveredRuntimeModel;
import com.aiconnect.llmgateway.admin.LmStudioModelDiscovery;
import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.domain.HealthStatus;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LmStudioLoadStateCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LmStudioModelDiscovery discovery = new LmStudioModelDiscovery(mapper);
    private final SecretCipher cipher = new SecretCipher(new GatewayProperties(
            "a".repeat(32), "b".repeat(32), "c".repeat(32), 30_000, 1_000, 1_000));

    @Test
    void liveServerRegressionEmptyV1InstancesAreSupplementedByExplicitV0StateAndContext() throws Exception {
        String nativeBody = """
                {"models":[{"key":"google/gemma-4-12b","display_name":"Gemma 4 12B","type":"llm",
                  "quantization":{"name":"Q8_0"},"max_context_length":262144,"loaded_instances":[],
                  "config":{"context_length":4096,"n_gpu_layers":99},
                  "variants":["google/gemma-4-12b@q4_k_m","google/gemma-4-12b@q8_0"],
                  "capabilities":{"vision":true,"reasoning":{"default":"on"}}}]}
                """;
        AtomicReference<String> legacyBody = new AtomicReference<>("""
                {"data":[{"id":"google/gemma-4-12b","type":"vlm","state":"loaded",
                  "loaded_context_length":131271,"max_context_length":262144,"quantization":"Q8_0",
                  "capabilities":["tool_use"]}]}
                """);
        List<String> requested = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer inventory-test-token");
            respond(exchange, 200, exchange.getRequestURI().getPath().equals("/api/v1/models") ? nativeBody : legacyBody.get());
        });
        try {
            RuntimeEndpoint endpoint = endpoint(server);
            endpoint.configure(null, null, cipher.encrypt("inventory-test-token"), true, false, null);
            endpoint.recordInventoryHealth(false, false);
            RuntimeResult result = adapter().listModels(endpoint);
            List<DiscoveredRuntimeModel> models = discovery.discover(result.body(), RuntimeType.LM_STUDIO);
            assertThat(models).hasSize(1);
            DiscoveredRuntimeModel live = models.get(0);
            assertThat(live.loaded()).isTrue();
            assertThat(live.providerModelId()).isEqualTo("google/gemma-4-12b");
            assertThat(live.compatibilityKey()).isEqualTo("google/gemma-4-12b");
            assertThat(live.contextLength()).isEqualTo(131271);
            assertThat(live.capabilitiesJson()).contains("VISION", "REASONING", "TOOL_CALLING");
            JsonNode metadata = mapper.readTree(live.metadataJson());
            assertThat(metadata.path("loaded_instances").isEmpty()).isTrue(); // No invented instance IDs.
            assertThat(metadata.path("variants").size()).isEqualTo(2);
            assertThat(metadata.path("runtimeSettings").path("contextLength").asInt()).isEqualTo(131271);
            assertThat(metadata.path("runtimeSettings").path("maxContextLength").asInt()).isEqualTo(262144);
            assertThat(metadata.path("runtimeSettings").has("gpuLayers")).isFalse();
            assertThat(metadata.path("loadStateEvidence").path("source").asText()).isEqualTo("LM_STUDIO_V0");
            assertThat(metadata.path("loadStateEvidence").path("conflict").asBoolean()).isTrue();
            assertThat(requested).containsExactly("/api/v1/models", "/api/v0/models");
            endpoint.recordInventoryHealth(live.loaded(), false);
            assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);

            legacyBody.set("{\"data\":[{\"id\":\"google/gemma-4-12b\",\"state\":\"not-loaded\"}]}");
            models = discovery.discover(adapter().listModels(endpoint).body(), RuntimeType.LM_STUDIO);
            assertThat(models).hasSize(1).allMatch(model -> !model.loaded());
            endpoint.recordInventoryHealth(models.get(0).loaded(), false);
            assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.STANDBY);
        } finally { server.stop(0); }
    }

    @Test
    void explicitLaterUnloadOverridesStaleV1InstanceButUnknownStateDoesNot() throws Exception {
        AtomicReference<String> state = new AtomicReference<>("not-loaded");
        HttpServer server = server(exchange -> respond(exchange, 200,
                exchange.getRequestURI().getPath().equals("/api/v1/models") ? """
                {"models":[{"key":"model-key","type":"llm","max_context_length":65536,
                  "loaded_instances":[{"id":"custom-id","config":{"context_length":8192,"parallel":2}}]}]}
                """ : "{\"data\":[{\"id\":\"custom-id\",\"state\":\"" + state.get() + "\"}]}"));
        try {
            var model = discover(server).get(0);
            assertThat(model.loaded()).isFalse();
            assertThat(mapper.readTree(model.metadataJson()).path("runtimeSettings").has("contextLength")).isFalse();
            state.set("unrecognized");
            model = discover(server).get(0);
            assertThat(model.loaded()).isTrue();
            assertThat(model.providerModelId()).isEqualTo("custom-id");
            assertThat(model.compatibilityKey()).isEqualTo("model-key");
            assertThat(model.contextLength()).isEqualTo(8192);
            assertThat(model.maxConcurrency()).isEqualTo(2);
            assertThat(mapper.readTree(model.metadataJson()).path("loadStateEvidence").path("source").asText()).isEqualTo("LM_STUDIO_V1");
        } finally { server.stop(0); }
    }

    @Test
    void optionalLegacyErrorsAndMalformedJsonDoNotFailHealthyNativeApi() throws Exception {
        AtomicInteger legacyStatus = new AtomicInteger(404);
        AtomicReference<String> legacyBody = new AtomicReference<>("{}");
        HttpServer server = server(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v1/models")) respond(exchange, 200, """
                    {"models":[{"key":"gemma","loaded_instances":[{"id":"gemma","config":{"context_length":4096}}]}]}
                    """);
            else respond(exchange, legacyStatus.get(), legacyBody.get());
        });
        try {
            for (int status : new int[]{404, 401, 500, 200}) {
                legacyStatus.set(status);
                legacyBody.set(status == 200 ? "not JSON" : "{}");
                RuntimeResult result = adapter().listModels(endpoint(server));
                assertThat(result.isSuccessful()).isTrue();
                var model = discovery.discover(result.body(), RuntimeType.LM_STUDIO).get(0);
                assertThat(model.loaded()).isTrue();
                assertThat(model.contextLength()).isEqualTo(4096);
            }
        } finally { server.stop(0); }
    }

    @Test
    void failedPrimaryApiDoesNotUseLegacyToHideAnAuthenticationFailure() throws Exception {
        List<String> requested = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
        });
        try {
            assertThat(adapter().listModels(endpoint(server)).statusCode()).isEqualTo(401);
            assertThat(requested).containsExactly("/api/v1/models");
        } finally { server.stop(0); }
    }

    @Test
    void downloadedEntriesAreNeverLoadedByMembershipAndAbsentContextIsNotModelMaximum() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200,
                exchange.getRequestURI().getPath().equals("/api/v1/models") ? """
                {"models":[{"key":"downloaded","loaded_instances":[]},
                  {"key":"loaded","max_context_length":262144,"loaded_instances":[]}]}
                """ : "{\"data\":[{\"id\":\"downloaded\"},{\"id\":\"loaded\",\"state\":\"loaded\"}]}"));
        try {
            var models = discover(server);
            assertThat(models).extracting(DiscoveredRuntimeModel::loaded).containsExactly(false, true);
            assertThat(models.get(1).contextLength()).isNull();
            assertThat(mapper.readTree(models.get(1).metadataJson()).path("runtimeSettings").has("contextLength")).isFalse();
        } finally { server.stop(0); }
    }

    @Test
    void exactIdsKeepDifferentPublishersAndQuantizationsSeparateAndPreserveActiveCustomIdentifiers() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200,
                exchange.getRequestURI().getPath().equals("/api/v1/models") ? """
                {"models":[{"key":"publisher-a/gemma@q4","loaded_instances":[]}]}
                """ : """
                {"data":[{"id":"publisher-b/gemma@q4","state":"loaded","loaded_context_length":4096},
                  {"id":"publisher-a/gemma@q8","state":"not-loaded"}]}
                """));
        try {
            var models = discover(server);
            assertThat(models).extracting(DiscoveredRuntimeModel::providerModelId)
                    .containsExactly("publisher-a/gemma@q4", "publisher-b/gemma@q4");
            assertThat(models).extracting(DiscoveredRuntimeModel::loaded).containsExactly(false, true);
            assertThat(models.get(1).contextLength()).isEqualTo(4096);
        } finally { server.stop(0); }
    }

    @Test
    void embeddingLoadStateSupplementDoesNotInventChatCapability() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200,
                exchange.getRequestURI().getPath().equals("/api/v1/models") ? """
                {"models":[{"key":"embed","type":"embedding","loaded_instances":[]}]}
                """ : "{\"data\":[{\"id\":\"embed\",\"state\":\"loaded\"}]}"));
        try {
            var model = discover(server).get(0);
            assertThat(model.loaded()).isTrue();
            assertThat(model.capabilitiesJson()).contains("EMBEDDING").doesNotContain("CHAT_COMPLETION");
        } finally { server.stop(0); }
    }

    @Test
    void legacyCatalogKeyDoesNotOverrideAnExplicitCustomInstanceOrDuplicateIt() throws Exception {
        AtomicReference<String> legacyState = new AtomicReference<>("not-loaded");
        HttpServer server = server(exchange -> respond(exchange, 200,
                exchange.getRequestURI().getPath().equals("/api/v1/models") ? """
                {"models":[{"key":"gemma","loaded_instances":[
                  {"id":"custom-chat","config":{"context_length":8192}},
                  {"id":"custom-other","config":{"context_length":4096}}]}]}
                """ : "{\"data\":[{\"id\":\"gemma\",\"state\":\"" + legacyState.get() + "\",\"loaded_context_length\":65536}]}"));
        try {
            for (String state : List.of("not-loaded", "loaded")) {
                legacyState.set(state);
                var models = discover(server);
                assertThat(models).extracting(DiscoveredRuntimeModel::providerModelId).containsExactly("custom-chat", "custom-other");
                assertThat(models).allMatch(DiscoveredRuntimeModel::loaded);
                assertThat(models).extracting(DiscoveredRuntimeModel::contextLength).containsExactly(8192, 4096);
            }
        } finally { server.stop(0); }
    }

    @Test
    void nativePreparingStateAndLegacyLoadingRemainPreparationNotUnhealthy() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200,
                exchange.getRequestURI().getPath().equals("/api/v1/models") ? """
                {"models":[{"key":"gemma","loaded_instances":[]}]}
                """ : "{\"data\":[{\"id\":\"gemma\",\"state\":\"loading\"}]}"));
        try {
            RuntimeResult result = adapter().listModels(endpoint(server));
            var models = discovery.discover(result.body(), RuntimeType.LM_STUDIO);
            assertThat(models).hasSize(1).allMatch(model -> !model.loaded());
            assertThat(discovery.hasPreparingModel(models)).isTrue();
            RuntimeEndpoint endpoint = endpoint(server);
            endpoint.recordInventoryHealth(false, discovery.hasPreparingModel(models), !discovery.hasUnknownLoadState(result.body(), models));
            assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.LOADING);
        } finally { server.stop(0); }
    }

    @Test
    void legacyOnlyServersUseActualContextAndRetainTheModelMaximumSeparately() throws Exception {
        HttpServer server = server(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v1/models")) respond(exchange, 404, "{}");
            else respond(exchange, 200, """
                    {"data":[{"id":"old-gemma","state":"loaded","loaded_context_length":4096,"max_context_length":65536}]}
                    """);
        });
        try {
            var model = discover(server).get(0);
            assertThat(model.loaded()).isTrue();
            assertThat(model.contextLength()).isEqualTo(4096);
            JsonNode metadata = mapper.readTree(model.metadataJson());
            assertThat(metadata.path("runtimeSettings").path("maxContextLength").asInt()).isEqualTo(65536);
            assertThat(metadata.path("loadStateEvidence").path("source").asText()).isEqualTo("LM_STUDIO_V0");
        } finally { server.stop(0); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "TEST_LMSTUDIO_BASE_URL", matches = ".+")
    void liveInventoryReadOnlyCheck() throws Exception {
        RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO,
                System.getenv("TEST_LMSTUDIO_BASE_URL"), null);
        RuntimeResult result = adapter().listModels(endpoint);
        assertThat(result.isSuccessful()).isTrue();
        List<DiscoveredRuntimeModel> models = discovery.discover(result.body(), RuntimeType.LM_STUDIO);
        assertThat(models).isNotEmpty();
        List<DiscoveredRuntimeModel> loaded = models.stream().filter(DiscoveredRuntimeModel::loaded).toList();
        assertThat(loaded).isNotEmpty();
        endpoint.recordInventoryHealth(!loaded.isEmpty(), discovery.hasPreparingModel(models), !discovery.hasUnknownLoadState(result.body(), models));
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
        for (DiscoveredRuntimeModel model : loaded) {
            JsonNode metadata = mapper.readTree(model.metadataJson());
            System.out.printf("LM_STUDIO_LIVE_CHECK model=%s state=%s context=%s source=%s conflict=%s%n",
                    model.providerModelId(), metadata.path("runtimeState").asText(), model.contextLength(),
                    metadata.path("loadStateEvidence").path("source").asText(), metadata.path("loadStateEvidence").path("conflict").asBoolean());
        }
    }

    private List<DiscoveredRuntimeModel> discover(HttpServer server) {
        return discovery.discover(adapter().listModels(endpoint(server)).body(), RuntimeType.LM_STUDIO);
    }

    private LmStudioRuntimeClient adapter() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        return new LmStudioRuntimeClient(RestClient.builder().requestFactory(factory).build(), mapper, cipher);
    }

    private RuntimeEndpoint endpoint(HttpServer server) {
        return new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", null);
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
