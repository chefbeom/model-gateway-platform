package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.security.ExternalUrlSafetyValidator;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ExternalUrlSafetyValidator urlSafetyValidator;

    public StreamingOpenAiRuntimeClient(ObjectMapper objectMapper, SecretCipher secretCipher,
                                        GatewayProperties properties) {
        this(objectMapper, secretCipher, properties, new ExternalUrlSafetyValidator(false));
    }

    @Autowired
    public StreamingOpenAiRuntimeClient(ObjectMapper objectMapper, SecretCipher secretCipher,
                                        GatewayProperties properties,
                                        ExternalUrlSafetyValidator urlSafetyValidator) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
        this.properties = properties;
        this.urlSafetyValidator = urlSafetyValidator;
    }

    public StreamingRuntimeResult chatCompletion(ExternalProvider provider, JsonNode requestBody) {
        return chatCompletion(provider, requestBody, false);
    }

    public StreamingRuntimeResult chatCompletion(ExternalProvider provider, JsonNode requestBody, boolean preserveTemperature) {
        urlSafetyValidator.assertSafeDestination(provider.getBaseUrl());
        try {
            String key = secretCipher.decrypt(provider.getEncryptedApiKey());
            String baseUrl = provider.getBaseUrl().trim().replaceAll("/+$", "");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMillis(properties.responseTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                            OpenAiRequestNormalizer.forExternalProvider(requestBody, preserveTemperature))));
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
