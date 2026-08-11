package com.aiconnect.llmgateway.service;

import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.ApiKeyStatus;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ApiKeyService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "sk_llmg_";
    private static final long MIN_TEMPORARY_KEY_SECONDS = 60;
    private static final long MAX_TEMPORARY_KEY_SECONDS = 24 * 60 * 60;
    private final ApiKeyRepository apiKeys;
    private final ProjectRepository projects;
    private final GatewayProperties properties;

    public ApiKeyService(ApiKeyRepository apiKeys, ProjectRepository projects, GatewayProperties properties) {
        this.apiKeys = apiKeys;
        this.projects = projects;
        this.properties = properties;
    }

    @Transactional
    public IssuedApiKey issue(UUID projectId, String name, Instant expiresAt) {
        return issue(projectId, name, expiresAt, null);
    }

    @Transactional
    public IssuedApiKey issue(UUID projectId, String name, Instant expiresAt, UUID issuedByUserId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
        if (!"ACTIVE".equalsIgnoreCase(project.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_SUSPENDED",
                    "This project is suspended. Resume it before issuing a new API key.");
        }
        String publicId = randomHex(8);
        String raw = PREFIX + publicId + "." + randomHex(32);
        ApiKey key = apiKeys.save(new ApiKey(projectId, issuedByUserId, name, PREFIX + publicId, hmac(raw), expiresAt));
        return new IssuedApiKey(key.getId(), key.getName(), key.getKeyPrefix(), raw, key.getExpiresAt());
    }

    @Transactional
    public IssuedApiKey issueTemporary(UUID projectId, String name, long durationSeconds, UUID issuedByUserId) {
        if (durationSeconds < MIN_TEMPORARY_KEY_SECONDS || durationSeconds > MAX_TEMPORARY_KEY_SECONDS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEMPORARY_API_KEY_DURATION_INVALID",
                    "Temporary API key duration must be between 60 seconds and 24 hours.");
        }
        return issue(projectId, name, Instant.now().plusSeconds(durationSeconds), issuedByUserId);
    }

    @Transactional
    public ApiKeyCredentials authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "A Bearer API key is required.");
        }
        String raw = authorization.substring("Bearer ".length()).trim();
        int delimiter = raw.indexOf('.');
        if (!raw.startsWith(PREFIX) || delimiter <= PREFIX.length()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "The API key format is invalid.");
        }
        String keyPrefix = raw.substring(0, delimiter);
        ApiKey key = apiKeys.findByKeyPrefix(keyPrefix)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "The API key is invalid."));
        if (!MessageDigest.isEqual(key.getSecretHash().getBytes(StandardCharsets.UTF_8), hmac(raw).getBytes(StandardCharsets.UTF_8)) || !key.isUsable(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "The API key is invalid, revoked, or expired.");
        }
        Project project = projects.findById(key.getProjectId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "The API key project no longer exists."));
        if (!"ACTIVE".equalsIgnoreCase(project.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_SUSPENDED",
                    "This project is suspended by an administrator. API requests are temporarily blocked.");
        }
        key.markUsed();
        return new ApiKeyCredentials(key, project);
    }

    @Transactional
    public void revoke(UUID id) {
        ApiKey key = apiKeys.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "The API key does not exist."));
        key.revoke();
    }

    /** Deletes only a key that was already revoked. Historical usage is detached, not deleted. */
    @Transactional
    public void deleteRevoked(UUID id) {
        ApiKey key = apiKeys.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "The API key does not exist."));
        if (key.getStatus() != ApiKeyStatus.REVOKED) {
            throw new ApiException(HttpStatus.CONFLICT, "API_KEY_DELETE_REQUIRES_REVOCATION",
                    "Revoke the API key before permanently deleting its record.");
        }
        apiKeys.delete(key);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.apiKeyPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash API key", exception);
        }
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
