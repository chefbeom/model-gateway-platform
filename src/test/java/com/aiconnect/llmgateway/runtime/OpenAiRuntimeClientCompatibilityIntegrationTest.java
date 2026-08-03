package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.ExternalProviderType;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRuntimeClientCompatibilityIntegrationTest {
    @Test
    void translatesLegacyTokenLimitBeforeSendingToExternalProvider() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<JsonNode> received = new AtomicReference<>();
        HttpServer providerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerServer.createContext("/v1/chat/completions", exchange -> {
            received.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = "{\"id\":\"test\",\"choices\":[],\"usage\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        providerServer.start();

        try {
            GatewayProperties properties = new GatewayProperties("admin", "pepper", "encryption-key",
                    0, 5_000, 5_000);
            SecretCipher cipher = new SecretCipher(properties);
            ExternalProvider provider = new ExternalProvider(UUID.randomUUID(), ExternalProviderType.OPENAI,
                    "Test provider", "http://127.0.0.1:" + providerServer.getAddress().getPort() + "/v1",
                    cipher.encrypt("provider-secret"));
            OpenAiRuntimeClient client = new OpenAiRuntimeClient(RestClient.builder().build(), objectMapper, cipher);
            ObjectNode request = (ObjectNode) objectMapper.readTree("""
                    {"model":"provider-model","messages":[],"max_tokens":4096}
                    """);

            RuntimeResult result = client.chatCompletion(provider, request);

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(received.get().path("max_completion_tokens").asInt()).isEqualTo(4096);
            assertThat(received.get().has("max_tokens")).isFalse();
            assertThat(request.path("max_tokens").asInt()).isEqualTo(4096);
        } finally {
            providerServer.stop(0);
        }
    }
}
