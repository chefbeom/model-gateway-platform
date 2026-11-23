package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_provider")
public class ExternalProvider {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID organizationId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private ExternalProviderType providerType;
    @Column(nullable = false, length = 160) private String displayName;
    @Column(nullable = false, length = 500) private String baseUrl;
    @Column(nullable = false, columnDefinition = "text") private String encryptedApiKey;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "zero_retention_verified", nullable = false) private boolean zeroRetentionVerified;
    @Column(name = "zero_retention_evidence", length = 500) private String zeroRetentionEvidence;
    @Column(name = "zero_retention_verified_at") private Instant zeroRetentionVerifiedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private HealthStatus healthStatus = HealthStatus.UNKNOWN;
    private Instant lastCheckedAt;
    private Instant lastSuccessAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected ExternalProvider() { }

    public ExternalProvider(UUID organizationId, ExternalProviderType providerType, String displayName,
                            String baseUrl, String encryptedApiKey) {
        this.organizationId = organizationId;
        this.providerType = providerType;
        this.displayName = displayName.trim();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.encryptedApiKey = encryptedApiKey;
    }

    public void configure(String displayName, String baseUrl, String encryptedApiKey,
                          boolean replaceApiKey, Boolean enabled) {
        if (displayName != null && !displayName.isBlank()) this.displayName = displayName.trim();
        if (baseUrl != null && !baseUrl.isBlank()) this.baseUrl = normalizeBaseUrl(baseUrl);
        if (replaceApiKey) this.encryptedApiKey = encryptedApiKey;
        if (enabled != null) this.enabled = enabled;
    }

    public void configureZeroRetention(Boolean verified, String evidence) {
        if (verified != null) {
            this.zeroRetentionVerified = verified;
            this.zeroRetentionEvidence = verified && evidence != null && !evidence.isBlank() ? evidence.trim() : null;
            this.zeroRetentionVerifiedAt = verified ? Instant.now() : null;
        } else if (evidence != null && this.zeroRetentionVerified) {
            this.zeroRetentionEvidence = evidence.isBlank() ? null : evidence.trim();
        }
    }

    public void recordHealth(boolean healthy) {
        lastCheckedAt = Instant.now();
        if (healthy) {
            healthStatus = HealthStatus.HEALTHY;
            lastSuccessAt = lastCheckedAt;
        } else healthStatus = HealthStatus.UNHEALTHY;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank() ? "https://api.openai.com/v1" : value.trim();
        return normalized.replaceAll("/+$", "");
    }

    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public ExternalProviderType getProviderType() { return providerType; }
    public String getDisplayName() { return displayName; }
    public String getBaseUrl() { return baseUrl; }
    public String getEncryptedApiKey() { return encryptedApiKey; }
    public boolean isEnabled() { return enabled; }
    public boolean isZeroRetentionVerified() { return zeroRetentionVerified; }
    public String getZeroRetentionEvidence() { return zeroRetentionEvidence; }
    public Instant getZeroRetentionVerifiedAt() { return zeroRetentionVerifiedAt; }
    public HealthStatus getHealthStatus() { return healthStatus; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getCreatedAt() { return createdAt; }
}
