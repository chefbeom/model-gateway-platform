package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;

/** Adapter for Ollama's native model catalog plus its OpenAI-compatible chat endpoint. */
@Component
public class OllamaRuntimeAdapter implements RuntimeProviderAdapter {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;

    public OllamaRuntimeAdapter(@Qualifier("runtimeRestClient") RestClient runtimeRestClient,
                                ObjectMapper objectMapper, SecretCipher secretCipher) {
        this.client = runtimeRestClient;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
    }

    @Override
    public boolean supports(RuntimeType runtimeType) { return runtimeType == RuntimeType.OLLAMA; }

    @Override
    public RuntimeResult listModels(RuntimeEndpoint endpoint) {
        RuntimeResult nativeResult = get(endpoint, "/api/tags");
        if (!nativeResult.isSuccessful()) return nativeResult;
        return new RuntimeResult(nativeResult.statusCode(), normalizeModels(nativeResult.body()));
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
            throw new RuntimeUnavailableException("The Ollama endpoint is unreachable.", exception);
        }
    }

    private RuntimeResult get(RuntimeEndpoint endpoint, String path) {
        try {
            return client.get().uri(RuntimeUrl.append(endpoint.getBaseUrl(), path))
                    .headers(headers -> applyAuthorization(headers, endpoint))
                    .exchange((request, response) -> toResult(response.getStatusCode().value(), response.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The Ollama endpoint is unreachable.", exception);
        }
    }

    private ObjectNode normalizeModels(JsonNode body) {
        ObjectNode normalized = objectMapper.createObjectNode().put("object", "list");
        ArrayNode data = normalized.putArray("data");
        for (JsonNode model : body.path("models")) {
            String name = model.path("name").asText("").trim();
            if (name.isBlank()) continue;
            ObjectNode item = data.addObject().put("id", name).put("object", "model").put("owned_by", "ollama");
            JsonNode details = model.path("details");
            if (details.isObject()) {
                if (details.hasNonNull("family")) item.put("arch", details.path("family").asText());
                if (details.hasNonNull("parameter_size")) item.put("parameter_size", details.path("parameter_size").asText());
                if (details.hasNonNull("quantization_level")) item.put("quantization", details.path("quantization_level").asText());
            }
            if (model.has("size")) item.put("size", model.path("size").asLong());
        }
        return normalized;
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