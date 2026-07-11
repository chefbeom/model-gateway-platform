package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.config.RuntimeProxySettings;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class StreamingLmStudioRuntimeClient {
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;
    private final GatewayProperties properties;

    public StreamingLmStudioRuntimeClient(ObjectMapper objectMapper, SecretCipher secretCipher,
                                          GatewayProperties properties, RuntimeProxySettings proxySettings) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()));
        if (proxySettings.enabled()) builder.proxy(ProxySelector.of(proxySettings.address()));
        this.client = builder.build();
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
        this.properties = properties;
    }

    public StreamingRuntimeResult chatCompletion(RuntimeEndpoint endpoint, JsonNode requestBody) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint.getBaseUrl() + "/v1/chat/completions"))
                    .timeout(Duration.ofMillis(properties.responseTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)));
            String token = secretCipher.decrypt(endpoint.getApiToken());
            if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
            HttpResponse<java.io.InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new StreamingRuntimeResult(response.statusCode(), response.body());
        } catch (IOException exception) {
            throw new RuntimeUnavailableException("The runtime endpoint is unreachable.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeUnavailableException("The runtime request was interrupted.", exception);
        }
    }
}
