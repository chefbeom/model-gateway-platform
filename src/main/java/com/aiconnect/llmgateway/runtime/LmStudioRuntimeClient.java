package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.io.IOException;

@Component
public class LmStudioRuntimeClient implements RuntimeProviderAdapter {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;

    public LmStudioRuntimeClient(@Qualifier("runtimeRestClient") RestClient runtimeRestClient, ObjectMapper objectMapper, SecretCipher secretCipher) {
        this.client = runtimeRestClient; this.objectMapper = objectMapper; this.secretCipher = secretCipher;
    }

    @Override
    public boolean supports(com.aiconnect.llmgateway.domain.RuntimeType runtimeType) {
        return runtimeType == com.aiconnect.llmgateway.domain.RuntimeType.LM_STUDIO;
    }

    @Override
    public RuntimeResult listModels(RuntimeEndpoint endpoint) {
        try {
            RuntimeResult nativeResult = getModels(endpoint, "/api/v1/models");
            if (nativeResult.statusCode() != 404) return supplementLoadState(endpoint, nativeResult);
            RuntimeResult legacyResult = getModels(endpoint, "/api/v0/models");
            return legacyResult.statusCode() == 404 ? getModels(endpoint, "/v1/models") : legacyResult;
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The runtime endpoint is unreachable.", exception);
        }
    }

    private RuntimeResult supplementLoadState(RuntimeEndpoint endpoint, RuntimeResult nativeResult) {
        if (!nativeResult.isSuccessful() || !nativeResult.body().path("models").isArray()) return nativeResult;
        try {
            // Some LM Studio versions return an empty v1 loaded_instances array
            // even while v0 reports an active model. Keep the rich v1 catalog,
            // and collect fresh, explicit v0 state without using the JIT catalog
            // at /v1/models as evidence of a loaded model.
            RuntimeResult legacy = getModels(endpoint, "/api/v0/models");
            if (legacy.isSuccessful() && legacy.body().path("data").isArray()) {
                ObjectNode combined = ((ObjectNode) nativeResult.body()).deepCopy();
                combined.set("lm_studio_legacy_models", legacy.body().path("data").deepCopy());
                return new RuntimeResult(nativeResult.statusCode(), combined);
            }
        } catch (RestClientException ignored) {
            // This is an optional compatibility check, not the primary probe.
            // A missing, malformed or unreachable v0 API must not invalidate v1.
        }
        return nativeResult;
    }

    @Override
    public RuntimeResult chatCompletion(RuntimeEndpoint endpoint, JsonNode request) {
        try {
            return client.post().uri(RuntimeUrl.openAi(endpoint.getBaseUrl(), "/chat/completions"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyAuthorization(headers, endpoint))
                    .body(request)
                    .exchange((clientRequest, response) -> toResult(response.getStatusCode().value(), response.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The runtime endpoint is unreachable.", exception);
        }
    }

    private RuntimeResult getModels(RuntimeEndpoint endpoint, String path) {
        return client.get().uri(RuntimeUrl.root(endpoint.getBaseUrl(), path))
                .headers(headers -> applyAuthorization(headers, endpoint))
                .exchange((request, response) -> toResult(response.getStatusCode().value(), response.getBody()));
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
