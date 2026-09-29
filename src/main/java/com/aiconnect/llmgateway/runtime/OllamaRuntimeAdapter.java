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
        RuntimeResult runningResult;
        try {
            runningResult = get(endpoint, "/api/ps");
        } catch (RuntimeUnavailableException exception) {
            // Older Ollama installations may not expose /api/ps. The downloaded
            // catalog is still useful, but no candidate is inferred to be loaded.
            runningResult = new RuntimeResult(0, objectMapper.createObjectNode());
        }
        return new RuntimeResult(nativeResult.statusCode(), normalizeModels(nativeResult.body(),
                runningResult.isSuccessful() ? runningResult.body() : null));
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

    private ObjectNode normalizeModels(JsonNode body, JsonNode running) {
        ObjectNode normalized = objectMapper.createObjectNode().put("object", "list");
        ArrayNode data = normalized.putArray("data");
        java.util.Map<String, JsonNode> runningByName = new java.util.LinkedHashMap<>();
        for (JsonNode model : running == null ? objectMapper.createArrayNode() : running.path("models")) {
            String name = modelName(model);
            if (!name.isBlank()) runningByName.put(name.toLowerCase(java.util.Locale.ROOT), model);
        }
        java.util.Set<String> emitted = new java.util.LinkedHashSet<>();
        for (JsonNode model : body.path("models")) {
            String name = modelName(model);
            if (name.isBlank()) continue;
            String key = name.toLowerCase(java.util.Locale.ROOT);
            JsonNode live = runningByName.get(key);
            ObjectNode item = normalizedModel(model, live);
            data.add(item);
            emitted.add(key);
        }
        // The running list is authoritative for loaded state and may briefly
        // include an item omitted from /api/tags while a model is being removed.
        for (JsonNode live : runningByName.values()) {
            String name = modelName(live);
            String key = name.toLowerCase(java.util.Locale.ROOT);
            if (!name.isBlank() && emitted.add(key)) data.add(normalizedModel(live, live));
        }
        return normalized;
    }

    private ObjectNode normalizedModel(JsonNode model, JsonNode live) {
        String name = modelName(model);
        ObjectNode item = objectMapper.createObjectNode().put("id", name).put("object", "model").put("owned_by", "ollama");
        boolean loaded = live != null;
        item.put("state", loaded ? "loaded" : "unloaded");
        JsonNode details = model.path("details").isObject() ? model.path("details") : live == null ? objectMapper.createObjectNode() : live.path("details");
        if (details.isObject()) {
            if (details.hasNonNull("family")) item.put("arch", details.path("family").asText());
            if (details.hasNonNull("parameter_size")) item.put("parameter_size", details.path("parameter_size").asText());
            if (details.hasNonNull("quantization_level")) item.put("quantization", details.path("quantization_level").asText());
        }
        JsonNode sizeSource = model.hasNonNull("size") ? model : live;
        if (sizeSource != null && sizeSource.hasNonNull("size")) item.put("size", sizeSource.path("size").asLong());
        if (loaded && live.hasNonNull("context_length")) item.put("context_length", live.path("context_length").asInt());

        ObjectNode settings = item.putObject("runtimeSettings").put("source", "OLLAMA_API");
        if (loaded) {
            if (live.hasNonNull("context_length")) settings.put("contextLength", live.path("context_length").asInt());
            if (live.hasNonNull("size_vram")) settings.put("vramBytes", live.path("size_vram").asLong());
            if (live.hasNonNull("size")) settings.put("sizeBytes", live.path("size").asLong());
            if (live.hasNonNull("expires_at")) settings.put("expiresAt", live.path("expires_at").asText());
            if (details.hasNonNull("quantization_level")) settings.put("quantization", details.path("quantization_level").asText());
        }
        return item;
    }

    private String modelName(JsonNode model) {
        String name = model.path("name").asText("").trim();
        if (name.isBlank()) name = model.path("model").asText("").trim();
        return name;
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
