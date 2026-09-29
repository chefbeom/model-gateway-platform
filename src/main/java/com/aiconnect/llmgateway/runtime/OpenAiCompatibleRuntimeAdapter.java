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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
        if (endpoint.getRuntimeType() != RuntimeType.LLAMA_CPP) return getOpenAi(endpoint, "/models");

        RuntimeResult routerCatalog = normalizeRouterCatalog(getRoot(endpoint, "/models"));
        RuntimeResult servedModels = getOpenAi(endpoint, "/models");
        if (!routerCatalog.isSuccessful()) {
            return servedModels.isSuccessful() ? enrichWithLoadedModelProps(endpoint, servedModels) : routerCatalog;
        }
        if (!servedModels.isSuccessful()) return enrichWithLoadedModelProps(endpoint, routerCatalog);
        return enrichWithLoadedModelProps(endpoint, mergeInventories(routerCatalog, servedModels));
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

    private RuntimeResult getOpenAi(RuntimeEndpoint endpoint, String path) {
        try {
            return client.get().uri(RuntimeUrl.openAi(endpoint.getBaseUrl(), path))
                    .headers(headers -> applyAuthorization(headers, endpoint))
                    .exchange((request, response) -> toResult(response.getStatusCode().value(), response.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The runtime endpoint is unreachable.", exception);
        }
    }

    private RuntimeResult getRoot(RuntimeEndpoint endpoint, String path) {
        try {
            return client.get().uri(RuntimeUrl.root(endpoint.getBaseUrl(), path))
                    .headers(headers -> applyAuthorization(headers, endpoint))
                    .exchange((request, response) -> toResult(response.getStatusCode().value(), response.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The llama.cpp router inventory endpoint is unreachable.", exception);
        }
    }

    private RuntimeResult mergeInventories(RuntimeResult routerCatalog, RuntimeResult servedModels) {
        com.fasterxml.jackson.databind.node.ObjectNode combined = routerCatalog.body() != null && routerCatalog.body().isObject()
                ? ((com.fasterxml.jackson.databind.node.ObjectNode) routerCatalog.body()).deepCopy()
                : objectMapper.createObjectNode();
        if (servedModels.body() != null && servedModels.body().path("data").isArray()) {
            combined.set("data", servedModels.body().path("data").deepCopy());
        }
        return new RuntimeResult(routerCatalog.statusCode(), combined);
    }

    /**
     * llama.cpp router's root GET /models uses a `data` catalog, while its
     * OpenAI-compatible GET /v1/models also uses `data` for currently served
     * models. Normalize the router list to `models` before merging so the two
     * distinct inventories cannot overwrite one another.
     */
    private RuntimeResult normalizeRouterCatalog(RuntimeResult routerCatalog) {
        if (routerCatalog.body() == null || !routerCatalog.body().isObject()) return routerCatalog;
        com.fasterxml.jackson.databind.node.ObjectNode body = ((com.fasterxml.jackson.databind.node.ObjectNode) routerCatalog.body()).deepCopy();
        if (!body.path("models").isArray() && body.path("data").isArray()) {
            body.set("models", body.path("data").deepCopy());
            body.remove("data");
        }
        return new RuntimeResult(routerCatalog.statusCode(), body);
    }

    private RuntimeResult enrichWithLoadedModelProps(RuntimeEndpoint endpoint, RuntimeResult inventory) {
        if (inventory.body() == null) return inventory;
        com.fasterxml.jackson.databind.node.ObjectNode body = inventory.body().isObject()
                ? ((com.fasterxml.jackson.databind.node.ObjectNode) inventory.body()).deepCopy()
                : objectMapper.createObjectNode();
        String inventoryField = body.path("data").isArray() ? "data" : "models";
        if (!body.path(inventoryField).isArray()) return inventory;
        com.fasterxml.jackson.databind.node.ArrayNode data = (com.fasterxml.jackson.databind.node.ArrayNode) body.path(inventoryField);
        for (int index = 0; index < data.size(); index++) {
            JsonNode model = data.path(index);
            String id = model.path("id").asText("").trim();
            if (id.isBlank() || !isRuntimeLoaded(model)) continue;
            JsonNode props = getOptionalModelProps(endpoint, id);
            if (props != null && props.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) data.path(index)).set("props", props);
        }
        return new RuntimeResult(inventory.statusCode(), body);
    }

    private JsonNode getOptionalModelProps(RuntimeEndpoint endpoint, String modelId) {
        String query = "?model=" + URLEncoder.encode(modelId, StandardCharsets.UTF_8) + "&autoload=false";
        try {
            return client.get().uri(URI.create(RuntimeUrl.root(endpoint.getBaseUrl(), "/props") + query))
                    .headers(headers -> applyAuthorization(headers, endpoint))
                    .exchange((request, response) -> {
                        RuntimeResult result = toResult(response.getStatusCode().value(), response.getBody());
                        return result.isSuccessful() ? result.body() : null;
                    });
        } catch (RestClientException | IllegalArgumentException ignored) {
            // /props is optional and must never turn an otherwise successful
            // model inventory into an endpoint failure.
            return null;
        }
    }

    private boolean isRuntimeLoaded(JsonNode model) {
        String state = model.path("status").path("value").asText(model.path("state").asText(""));
        if (state.isBlank()) return true; // /v1/models is the loaded-only llama.cpp view.
        return switch (state.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "loaded", "ready", "running" -> true;
            default -> false;
        };
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
