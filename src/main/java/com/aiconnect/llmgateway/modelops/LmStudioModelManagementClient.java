package com.aiconnect.llmgateway.modelops;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import com.aiconnect.llmgateway.security.ExternalUrlSafetyValidator;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;

@Component
public class LmStudioModelManagementClient {
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final Pattern SAFE_JOB_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private final RestClient client;
    private final ObjectMapper mapper;
    private final SecretCipher cipher;
    @Autowired(required = false)
    private ExternalUrlSafetyValidator urlSafetyValidator;

    public LmStudioModelManagementClient(@Qualifier("runtimeRestClient") RestClient client, ObjectMapper mapper, SecretCipher cipher) { this.client = client; this.mapper = mapper; this.cipher = cipher; }
    public RuntimeResult list(RuntimeEndpoint endpoint) { return get(endpoint, isLlamaCpp(endpoint) ? "/v1/models" : "/api/v1/models"); }
    public RuntimeResult load(RuntimeEndpoint endpoint, JsonNode payload) { return post(endpoint, isLlamaCpp(endpoint) ? "/models/load" : "/api/v1/models/load", payload); }
    public RuntimeResult unload(RuntimeEndpoint endpoint, JsonNode payload) { return post(endpoint, isLlamaCpp(endpoint) ? "/models/unload" : "/api/v1/models/unload", payload); }
    public RuntimeResult download(RuntimeEndpoint endpoint, JsonNode payload) { return post(endpoint, "/api/v1/models/download", payload); }
    public RuntimeResult downloadStatus(RuntimeEndpoint endpoint, String jobId) {
        if (jobId == null || !SAFE_JOB_ID.matcher(jobId).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JOB_ID", "Invalid download job identifier.");
        }
        assertSafeRuntimeDestination(endpoint);
        URI target = UriComponentsBuilder.fromUriString(endpoint.getBaseUrl())
                .pathSegment("api", "v1", "models", "download", "status", jobId)
                .build(true)
                .toUri();
        try {
            return client.get().uri(target).headers(headers -> auth(headers, endpoint))
                    .exchange((req, res) -> result(res.getStatusCode().value(), res.getBody()));
        } catch (RestClientException exception) {
            throw new RuntimeUnavailableException("The runtime model management API is unreachable.", exception);
        }
    }
    private RuntimeResult get(RuntimeEndpoint endpoint, String path) {
        assertSafeRuntimeDestination(endpoint);
        try { return client.get().uri(endpoint.getBaseUrl() + path).headers(headers -> auth(headers, endpoint)).exchange((req, res) -> result(res.getStatusCode().value(), res.getBody())); }
        catch (RestClientException exception) { throw new RuntimeUnavailableException("The runtime model management API is unreachable.", exception); }
    }
    private RuntimeResult post(RuntimeEndpoint endpoint, String path, JsonNode payload) {
        assertSafeRuntimeDestination(endpoint);
        try { return client.post().uri(endpoint.getBaseUrl() + path).contentType(MediaType.APPLICATION_JSON).headers(headers -> auth(headers, endpoint)).body(payload).exchange((req, res) -> result(res.getStatusCode().value(), res.getBody())); }
        catch (RestClientException exception) { throw new RuntimeUnavailableException("The runtime model management API is unreachable.", exception); }
    }
    private void assertSafeRuntimeDestination(RuntimeEndpoint endpoint) {
        ExternalUrlSafetyValidator validator = urlSafetyValidator != null ? urlSafetyValidator : new ExternalUrlSafetyValidator(false);
        validator.assertSafeRuntimeDestination(endpoint.getBaseUrl());
    }
    private void auth(HttpHeaders headers, RuntimeEndpoint endpoint) { String token = cipher.decrypt(endpoint.getApiToken()); if (token != null && !token.isBlank()) headers.setBearerAuth(token); }
    private boolean isLlamaCpp(RuntimeEndpoint endpoint) { return endpoint.getRuntimeType() == RuntimeType.LLAMA_CPP; }
    private RuntimeResult result(int status, java.io.InputStream body) throws IOException {
        byte[] raw = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (raw.length > MAX_RESPONSE_BYTES) {
            throw new IOException("Runtime response exceeded size limit.");
        }
        JsonNode parsed = raw.length == 0 ? null : mapper.readTree(raw);
        return new RuntimeResult(status, parsed == null ? mapper.createObjectNode() : parsed);
    }
}
