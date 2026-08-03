package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class StreamingOpenAiRuntimeClient {
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;
    private final GatewayProperties properties;

    public StreamingOpenAiRuntimeClient(ObjectMapper objectMapper, SecretCipher secretCipher,
                                        GatewayProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
        this.properties = properties;
    }

    public StreamingRuntimeResult chatCompletion(ExternalProvider provider, JsonNode requestBody) {
        try {
            String key = secretCipher.decrypt(provider.getEncryptedApiKey());
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(provider.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofMillis(properties.responseTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                            OpenAiRequestNormalizer.forExternalProvider(requestBody))));
            if (key != null && !key.isBlank()) request.header("Authorization", "Bearer " + key);
            HttpResponse<java.io.InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new StreamingRuntimeResult(response.statusCode(), response.body());
        } catch (IOException exception) {
            throw new RuntimeUnavailableException("The external OpenAI provider is unreachable.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeUnavailableException("The external provider request was interrupted.", exception);
        }
    }
}
