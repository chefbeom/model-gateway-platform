package com.aiconnect.llmgateway.service;

import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.ApiKeyStatus;
import com.aiconnect.llmgateway.domain.DataBoundaryPolicy;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired(required = false)
    private OrganizationRepository organizations;
    @Autowired(required = false)
    private com.aiconnect.llmgateway.beta.BetaAccessService betaAccess;

    public ApiKeyService(ApiKeyRepository apiKeys, ProjectRepository projects, GatewayProperties properties) {
        this.apiKeys = apiKeys;
        this.projects = projects;
        this.properties = properties;
    }

    @Transactional
    public IssuedApiKey issue(UUID projectId, String name, Instant expiresAt) {
        return issue(projectId, name, expiresAt, null, null);
    }

    @Transactional
    public IssuedApiKey issue(UUID projectId, String name, Instant expiresAt, UUID issuedByUserId) {
        return issue(projectId, name, expiresAt, issuedByUserId, null);
    }

    @Transactional
    public IssuedApiKey issue(UUID projectId, String name, Instant expiresAt, UUID issuedByUserId,
                              DataBoundaryPolicy requestedBoundary) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
        assertOrganizationActive(project.getOrganizationId());
        if (!"ACTIVE".equalsIgnoreCase(project.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_SUSPENDED",
                    "This project is suspended. Resume it before issuing a new API key.");
        }
        DataBoundaryPolicy projectEffective = project.effectiveDataBoundaryPolicy();
        DataBoundaryPolicy keyBoundary = requestedBoundary == null ? projectEffective : requestedBoundary;
        if (keyBoundary.exceeds(projectEffective)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DATA_BOUNDARY_RELAXATION_DENIED",
                    "API key data boundary cannot exceed the project data boundary policy.");
        }
        String publicId = randomHex(8);
        String raw = PREFIX + publicId + "." + randomHex(32);
        ApiKey entity = new ApiKey(projectId, issuedByUserId, name, PREFIX + publicId, hmac(raw), expiresAt);
        entity.configureDataBoundaryPolicy(keyBoundary);
        ApiKey key = apiKeys.save(entity);
        return new IssuedApiKey(key.getId(), key.getName(), key.getKeyPrefix(), raw, key.getExpiresAt());
    }

    @Transactional
    public IssuedApiKey issueTemporary(UUID projectId, String name, long durationSeconds, UUID issuedByUserId) {
        return issueTemporary(projectId, name, durationSeconds, issuedByUserId, null);
    }

    @Transactional
    public IssuedApiKey issueTemporary(UUID projectId, String name, long durationSeconds, UUID issuedByUserId,
                                       DataBoundaryPolicy requestedBoundary) {
        if (durationSeconds < MIN_TEMPORARY_KEY_SECONDS || durationSeconds > MAX_TEMPORARY_KEY_SECONDS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEMPORARY_API_KEY_DURATION_INVALID",
                    "Temporary API key duration must be between 60 seconds and 24 hours.");
        }
        return issue(projectId, name, Instant.now().plusSeconds(durationSeconds), issuedByUserId, requestedBoundary);
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
        assertOrganizationActive(project.getOrganizationId());
        if (!"ACTIVE".equalsIgnoreCase(project.getStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_SUSPENDED",
                    "This project is suspended by an administrator. API requests are temporarily blocked.");
        }
        if (betaAccess != null && betaAccess.isBetaProject(project.getId())) betaAccess.checkActiveProject(project.getId());
        if (apiKeys.markUsedIfActive(key.getId(), ApiKeyStatus.ACTIVE, Instant.now()) != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "The API key is invalid, revoked, or expired.");
        }
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

    private void assertOrganizationActive(UUID organizationId) {
        if (organizations == null || organizationId == null) return;
        organizations.findById(organizationId).ifPresent(org -> {
            if (!"ACTIVE".equalsIgnoreCase(org.getStatus())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_SUSPENDED",
                        "This organization is suspended by an administrator.");
            }
        });
    }
}
