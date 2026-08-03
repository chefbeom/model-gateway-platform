package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public OpenAiRuntimeClient(@Qualifier("notificationRestClient") RestClient client,
                               ObjectMapper objectMapper, SecretCipher secretCipher) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
    }

    public RuntimeResult listModels(ExternalProvider provider) {
        try {
            return client.get().uri(provider.getBaseUrl() + "/models")
                    .headers(headers -> applyAuthorization(headers, provider))
                    .exchange((request, response) -> toResult(response.getStatusCode().value(), response.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The external OpenAI provider is unreachable.", exception);
        }
    }

    public RuntimeResult chatCompletion(ExternalProvider provider, JsonNode request) {
        try {
            return client.post().uri(provider.getBaseUrl() + "/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyAuthorization(headers, provider))
                    .body(OpenAiRequestNormalizer.forExternalProvider(request))
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
        JsonNode parsed = objectMapper.readTree(body);
        return new RuntimeResult(statusCode, parsed == null ? objectMapper.createObjectNode() : parsed);
    }
}
