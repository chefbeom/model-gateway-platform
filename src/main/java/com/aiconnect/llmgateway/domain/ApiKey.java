package com.aiconnect.llmgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_key")
public class ApiKey {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID projectId;
    @Column(columnDefinition = "char(36)") private UUID issuedByUserId;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 48, unique = true) private String keyPrefix;
    @Column(nullable = false, length = 64) private String secretHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private ApiKeyStatus status = ApiKeyStatus.ACTIVE;
    private Instant expiresAt;
    private Instant lastUsedAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    protected ApiKey() { }

    public ApiKey(UUID projectId, String name, String keyPrefix, String secretHash, Instant expiresAt) {
        this(projectId, null, name, keyPrefix, secretHash, expiresAt);
    }

    public ApiKey(UUID projectId, UUID issuedByUserId, String name, String keyPrefix, String secretHash, Instant expiresAt) {
        this.projectId = projectId;
        this.issuedByUserId = issuedByUserId;
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.secretHash = secretHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) { return status == ApiKeyStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now)); }
    public void markUsed() { lastUsedAt = Instant.now(); }
    public void revoke() { status = ApiKeyStatus.REVOKED; }
    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getIssuedByUserId() { return issuedByUserId; }
    public String getName() { return name; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getSecretHash() { return secretHash; }
    public ApiKeyStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
