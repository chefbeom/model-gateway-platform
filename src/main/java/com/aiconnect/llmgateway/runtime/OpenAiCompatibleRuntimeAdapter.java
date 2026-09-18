package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
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

/** Adapter for llama.cpp and other local OpenAI-compatible servers. */
@Component
public class OpenAiCompatibleRuntimeAdapter implements RuntimeProviderAdapter {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;

    public OpenAiCompatibleRuntimeAdapter(@Qualifier("runtimeRestClient") RestClient runtimeRestClient,
                                          ObjectMapper objectMapper, SecretCipher secretCipher) {
        this.client = runtimeRestClient;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
    }

    @Override
    public boolean supports(RuntimeType runtimeType) {
        return runtimeType == RuntimeType.LLAMA_CPP || runtimeType == RuntimeType.OPENAI_COMPATIBLE;
    }

    @Override
    public RuntimeResult listModels(RuntimeEndpoint endpoint) {
        return get(endpoint, "/models");
    }

    @Override
    public RuntimeResult chatCompletion(RuntimeEndpoint endpoint, JsonNode request) {
        try {
            return client.post().uri(RuntimeUrl.openAi(endpoint.getBaseUrl(), "/chat/completions"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyAuthorization(headers, endpoint))
                    .body(request)
                    .exchange((requestMessage, response) -> toResult(response.getStatusCode().value(), response.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The runtime endpoint is unreachable.", exception);
        }
    }

    private RuntimeResult get(RuntimeEndpoint endpoint, String path) {
        try {
            return client.get().uri(RuntimeUrl.openAi(endpoint.getBaseUrl(), path))
                    .headers(headers -> applyAuthorization(headers, endpoint))
                    .exchange((request, response) -> toResult(response.getStatusCode().value(), response.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The runtime endpoint is unreachable.", exception);
        }
    }

    private void applyAuthorization(HttpHeaders headers, RuntimeEndpoint endpoint) {
        String token = secretCipher.decrypt(endpoint.getApiToken());
        if (token != null && !token.isBlank()) headers.setBearerAuth(token);
    }

    private RuntimeResult toResult(int statusCode, java.io.InputStream body) throws IOException {
        JsonNode parsed = objectMapper.readTree(body);
        return new RuntimeResult(statusCode, parsed == null ? objectMapper.createObjectNode() : parsed);
    }
}