package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.admin.DiscoveredRuntimeModel;
import com.aiconnect.llmgateway.admin.LmStudioModelDiscovery;
import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeModelInventoryAdapterIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void llamaCppMergesRouterCatalogAndLoadedViewAndAddsSafeProps() throws Exception {
        List<String> requested = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            String uri = exchange.getRequestURI().toString();
            requested.add(uri);
            if (uri.equals("/models")) {
                respond(exchange, 200, """
                        {"data":[
                          {"id":"gemma-4-12b-it-qat-q4_0","path":"/opt/llm/models/gemma-4-12b-it-qat-q4_0.gguf","status":{"value":"unloaded","args":["-c","4096","-nkvo"]}},
                          {"id":"team/gemma-4-12b-it-Q8_0","path":"/opt/llm/models/gemma-4-12b-it-Q8_0.gguf","status":{"value":"loaded","args":["llama-server","-ctx","8192","-np","2","-ngl","40","-ctk","q8_0","-ctv","f16","-no-kv-offload","--api-key","must-not-persist","--apiKey=must-not-persist-camel"]}}
                        ]}
                        """);
            } else if (uri.equals("/v1/models")) {
                respond(exchange, 200, """
                        {"data":[{"id":"team/gemma-4-12b-it-Q8_0","object":"model"}]}
                        """);
            } else if (exchange.getRequestURI().getPath().equals("/props")) {
                respond(exchange, 200, """
                        {"model_path":"/opt/llm/models/gemma-4-12b-it-Q8_0.gguf","default_generation_settings":{"n_ctx":8192},"total_slots":2,"modalities":["text"]}
                        """);
            } else {
                respond(exchange, 404, "{}");
            }
        });
        server.start();

        try {
            RuntimeResult result = llamaCppAdapter(server).listModels(endpoint(server, RuntimeType.LLAMA_CPP, "/v1"));
            List<DiscoveredRuntimeModel> models = new LmStudioModelDiscovery(mapper).discover(result.body(), RuntimeType.LLAMA_CPP);

            assertThat(models).hasSize(2);
            assertThat(models).extracting(DiscoveredRuntimeModel::providerModelId)
                    .containsExactly("gemma-4-12b-it-qat-q4_0", "team/gemma-4-12b-it-Q8_0");
            assertThat(models).extracting(DiscoveredRuntimeModel::loaded).containsExactly(false, true);
            assertThat(models.get(1).contextLength()).isEqualTo(8192);
            JsonNode candidateSettings = mapper.readTree(models.get(0).metadataJson()).path("runtimeSettings");
            assertThat(candidateSettings.path("contextLength").asInt()).isEqualTo(4096);
            assertThat(candidateSettings.path("offloadKvCacheToGpu").asBoolean()).isFalse();
            JsonNode liveMetadata = mapper.readTree(models.get(1).metadataJson());
            JsonNode settings = liveMetadata.path("runtimeSettings");
            assertThat(liveMetadata.path("runtimeState").asText()).isEqualTo("LOADED");
            assertThat(settings.path("contextLength").asInt()).isEqualTo(8192);
            assertThat(settings.path("parallelSlots").asInt()).isEqualTo(2);
            assertThat(settings.path("gpuLayers").asInt()).isEqualTo(40);
            assertThat(settings.path("kvCacheTypeK").asText()).isEqualTo("q8_0");
            assertThat(settings.path("kvCacheTypeV").asText()).isEqualTo("f16");
            assertThat(settings.path("offloadKvCacheToGpu").asBoolean()).isFalse();
            assertThat(settings.path("modelPath").asText()).isEqualTo("/opt/llm/models/gemma-4-12b-it-Q8_0.gguf");
            assertThat(models.get(1).metadataJson()).doesNotContain("must-not-persist", "must-not-persist-camel", "api-key", "apiKey");
            assertThat(requested).contains("/models", "/v1/models");
            assertThat(requested).anyMatch(path -> path.startsWith("/props?") && path.contains("autoload=false"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void llamaCppStandaloneFallbackTreatsV1ModelsAsLoadedAndDoesNotRequireRouterRoute() throws Exception {
        HttpServer server = server(exchange -> {
            String uri = exchange.getRequestURI().toString();
            if (uri.equals("/models")) respond(exchange, 404, "{}");
            else if (uri.equals("/v1/models")) respond(exchange, 200,
                    "{\"data\":[{\"id\":\"standalone-model\",\"object\":\"model\"}]}");
            else if (exchange.getRequestURI().getPath().equals("/props")) respond(exchange, 404, "{}");
            else respond(exchange, 404, "{}");
        });
        server.start();
        try {
            RuntimeResult result = llamaCppAdapter(server).listModels(endpoint(server, RuntimeType.LLAMA_CPP, ""));
            List<DiscoveredRuntimeModel> models = new LmStudioModelDiscovery(mapper).discover(result.body(), RuntimeType.LLAMA_CPP);
            assertThat(models).hasSize(1);
            assertThat(models.get(0).loaded()).isTrue();
            assertThat(mapper.readTree(models.get(0).metadataJson()).path("runtimeState").asText()).isEqualTo("LOADED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ollamaUsesPsForLoadedStateAndRuntimeSettingsNotTags() throws Exception {
        HttpServer server = server(exchange -> {
            if (exchange.getRequestURI().toString().equals("/api/tags")) {
                respond(exchange, 200, """
                        {"models":[
                          {"name":"gemma4:12b","size":120000,"details":{"family":"gemma","quantization_level":"Q4_K_M"}},
                          {"name":"qwen:8b","size":80000,"details":{"family":"qwen","quantization_level":"Q8_0"}}
                        ]}
                        """);
            } else if (exchange.getRequestURI().toString().equals("/api/ps")) {
                respond(exchange, 200, """
                        {"models":[{"name":"gemma4:12b","model":"gemma4:12b","size":125000,"size_vram":96000,"context_length":32768,"expires_at":"2026-09-29T12:00:00Z","details":{"quantization_level":"Q4_K_M"}}]}
                        """);
            } else respond(exchange, 404, "{}");
        });
        server.start();
        try {
            GatewayProperties properties = properties();
            SecretCipher cipher = new SecretCipher(properties);
            OllamaRuntimeAdapter adapter = new OllamaRuntimeAdapter(
                    RestClient.builder().build(), mapper, cipher);
            RuntimeResult result = adapter.listModels(endpoint(server, RuntimeType.OLLAMA, ""));
            List<DiscoveredRuntimeModel> models = new LmStudioModelDiscovery(mapper).discover(result.body(), RuntimeType.OLLAMA);

            assertThat(models).hasSize(2);
            assertThat(models).extracting(DiscoveredRuntimeModel::loaded).containsExactly(true, false);
            JsonNode settings = mapper.readTree(models.get(0).metadataJson()).path("runtimeSettings");
            assertThat(settings.path("contextLength").asInt()).isEqualTo(32768);
            assertThat(settings.path("vramBytes").asLong()).isEqualTo(96000L);
            assertThat(settings.path("sizeBytes").asLong()).isEqualTo(125000L);
            assertThat(settings.path("expiresAt").asText()).isEqualTo("2026-09-29T12:00:00Z");
        } finally {
            server.stop(0);
        }
    }

    private OpenAiCompatibleRuntimeAdapter llamaCppAdapter(HttpServer server) {
        GatewayProperties properties = properties();
        return new OpenAiCompatibleRuntimeAdapter(
                RestClient.builder().build(),
                mapper, new SecretCipher(properties));
    }

    private GatewayProperties properties() {
        return new GatewayProperties("a".repeat(32), "b".repeat(32), "c".repeat(32), 30_000, 2_000, 10_000);
    }

    private RuntimeEndpoint endpoint(HttpServer server, RuntimeType type, String suffix) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + suffix;
        return new RuntimeEndpoint(UUID.randomUUID(), type, base, null);
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        return server;
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
