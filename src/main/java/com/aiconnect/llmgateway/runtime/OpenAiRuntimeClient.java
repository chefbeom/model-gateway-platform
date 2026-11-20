package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.security.ExternalUrlSafetyValidator;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;

@Component
public class OpenAiRuntimeClient {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;
    private final ExternalUrlSafetyValidator urlSafetyValidator;

    public OpenAiRuntimeClient(RestClient client, ObjectMapper objectMapper, SecretCipher secretCipher) {
        this(client, objectMapper, secretCipher, new ExternalUrlSafetyValidator(false));
    }

    @Autowired
    public OpenAiRuntimeClient(@Qualifier("externalProviderRestClient") RestClient client,
                               ObjectMapper objectMapper, SecretCipher secretCipher,
                               ExternalUrlSafetyValidator urlSafetyValidator) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
        this.urlSafetyValidator = urlSafetyValidator;
    }

    public RuntimeResult listModels(ExternalProvider provider) {
        urlSafetyValidator.assertSafeDestination(provider.getBaseUrl());
        try {
            return client.get().uri(normalizeBaseForRequest(provider.getBaseUrl()) + "/models")
                    .headers(headers -> applyAuthorization(headers, provider))
                    .exchange((request, response) -> toResult(response.getStatusCode().value(), response.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The external OpenAI provider is unreachable.", exception);
        }
    }

    public RuntimeResult chatCompletion(ExternalProvider provider, JsonNode request) {
        return chatCompletion(provider, request, false);
    }

    public RuntimeResult chatCompletion(ExternalProvider provider, JsonNode request, boolean preserveTemperature) {
        urlSafetyValidator.assertSafeDestination(provider.getBaseUrl());
        try {
            return client.post().uri(normalizeBaseForRequest(provider.getBaseUrl()) + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyAuthorization(headers, provider))
                    .body(OpenAiRequestNormalizer.forExternalProvider(request, preserveTemperature))
                    .exchange((clientRequest, response) -> toResult(response.getStatusCode().value(), response.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The external OpenAI provider is unreachable.", exception);
        }
    }

    private void applyAuthorization(HttpHeaders headers, ExternalProvider provider) {
        String key = secretCipher.decrypt(provider.getEncryptedApiKey());
        if (key != null && !key.isBlank()) headers.setBearerAuth(key);
    }

    private RuntimeResult toResult(int statusCode, java.io.InputStream body) throws IOException {
        byte[] raw = body == null ? new byte[0] : body.readAllBytes();
        if (raw.length == 0) {
            return new RuntimeResult(statusCode, objectMapper.createObjectNode());
        }
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            return new RuntimeResult(statusCode, parsed == null ? objectMapper.createObjectNode() : parsed);
        } catch (JsonProcessingException ex) {
            if (statusCode >= 300 && statusCode < 400) {
                return new RuntimeResult(statusCode, objectMapper.createObjectNode()
                        .put("error", "Upstream redirect response (HTTP " + statusCode + ") was not followed."));
            }
            throw ex;
        }
    }

    private static String normalizeBaseForRequest(String baseUrl) {
        return baseUrl.trim().replaceAll("/+$", "");
    }
}
