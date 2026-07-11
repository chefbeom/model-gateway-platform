package com.aiconnect.llmgateway.modelops;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
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
public class LmStudioModelManagementClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final SecretCipher cipher;
    public LmStudioModelManagementClient(@Qualifier("runtimeRestClient") RestClient client, ObjectMapper mapper, SecretCipher cipher) { this.client = client; this.mapper = mapper; this.cipher = cipher; }
    public RuntimeResult list(RuntimeEndpoint endpoint) { return get(endpoint, "/api/v1/models"); }
    public RuntimeResult load(RuntimeEndpoint endpoint, JsonNode payload) { return post(endpoint, "/api/v1/models/load", payload); }
    public RuntimeResult unload(RuntimeEndpoint endpoint, JsonNode payload) { return post(endpoint, "/api/v1/models/unload", payload); }
    public RuntimeResult download(RuntimeEndpoint endpoint, JsonNode payload) { return post(endpoint, "/api/v1/models/download", payload); }
    public RuntimeResult downloadStatus(RuntimeEndpoint endpoint, String jobId) { return get(endpoint, "/api/v1/models/download/status/" + jobId); }
    private RuntimeResult get(RuntimeEndpoint endpoint, String path) {
        try { return client.get().uri(endpoint.getBaseUrl() + path).headers(headers -> auth(headers, endpoint)).exchange((req, res) -> result(res.getStatusCode().value(), res.getBody())); }
        catch (RestClientException exception) { throw new RuntimeUnavailableException("The LM Studio model API is unreachable.", exception); }
    }
    private RuntimeResult post(RuntimeEndpoint endpoint, String path, JsonNode payload) {
        try { return client.post().uri(endpoint.getBaseUrl() + path).contentType(MediaType.APPLICATION_JSON).headers(headers -> auth(headers, endpoint)).body(payload).exchange((req, res) -> result(res.getStatusCode().value(), res.getBody())); }
        catch (RestClientException exception) { throw new RuntimeUnavailableException("The LM Studio model API is unreachable.", exception); }
    }
    private void auth(HttpHeaders headers, RuntimeEndpoint endpoint) { String token = cipher.decrypt(endpoint.getApiToken()); if (token != null && !token.isBlank()) headers.setBearerAuth(token); }
    private RuntimeResult result(int status, java.io.InputStream body) throws IOException { JsonNode parsed = mapper.readTree(body); return new RuntimeResult(status, parsed == null ? mapper.createObjectNode() : parsed); }
}
