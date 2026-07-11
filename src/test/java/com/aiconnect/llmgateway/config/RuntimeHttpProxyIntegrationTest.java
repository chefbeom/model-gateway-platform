package com.aiconnect.llmgateway.config;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.runtime.LmStudioRuntimeClient;
import com.aiconnect.llmgateway.runtime.StreamingLmStudioRuntimeClient;
import com.aiconnect.llmgateway.runtime.StreamingRuntimeResult;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeHttpProxyIntegrationTest {
    @Test
    void modelDiscoveryAndStreamingDelegateTailnetDnsToConfiguredHttpProxy() throws Exception {
        List<String> proxyTargets = new CopyOnWriteArrayList<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            proxyTargets.add(exchange.getRequestURI().toString());
            byte[] body;
            String contentType;
            if (exchange.getRequestMethod().equals("GET")) {
                body = "{\"models\":[{\"key\":\"proxy/model\",\"loaded_instances\":[]}]}".getBytes(StandardCharsets.UTF_8);
                contentType = "application/json";
            } else {
                exchange.getRequestBody().readAllBytes();
                body = "data: {\"model\":\"proxy/model\",\"choices\":[]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
                contentType = "text/event-stream";
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        proxy.start();

        try {
            GatewayProperties properties = new GatewayProperties("a".repeat(32), "b".repeat(32), "c".repeat(32),
                    30_000, 2_000, 10_000);
            RuntimeProxySettings proxySettings = new RuntimeProxySettings(
                    "http://127.0.0.1:" + proxy.getAddress().getPort());
            ObjectMapper objectMapper = new ObjectMapper();
            SecretCipher cipher = new SecretCipher(properties);
            RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO,
                    "http://tailnet-only.invalid:1234", cipher.encrypt(null));

            LmStudioRuntimeClient nonStreaming = new LmStudioRuntimeClient(
                    new HttpClientConfig().runtimeRestClient(properties, proxySettings), objectMapper, cipher);
            assertThat(nonStreaming.listModels(endpoint).statusCode()).isEqualTo(200);

            StreamingLmStudioRuntimeClient streaming = new StreamingLmStudioRuntimeClient(
                    objectMapper, cipher, properties, proxySettings);
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", "proxy/model");
            request.put("stream", true);
            StreamingRuntimeResult result = streaming.chatCompletion(endpoint, request);
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(new String(result.body().readAllBytes(), StandardCharsets.UTF_8)).contains("data: [DONE]");

            assertThat(proxyTargets).hasSize(2);
            assertThat(proxyTargets).allMatch(target -> target.contains("tailnet-only.invalid:1234"));
        } finally {
            proxy.stop(0);
        }
    }
}
