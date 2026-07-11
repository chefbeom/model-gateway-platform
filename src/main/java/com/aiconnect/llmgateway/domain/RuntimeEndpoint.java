package com.aiconnect.llmgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "runtime_endpoint")
@SQLRestriction("archived_at IS NULL")
public class RuntimeEndpoint {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID nodeId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private RuntimeType runtimeType;
    @Column(nullable = false, length = 500) private String baseUrl;
    @Column(columnDefinition = "text") private String encryptedApiToken;
    @Column(length = 80) private String runtimeVersion;
    @Column(nullable = false) private boolean enabled = true;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private HealthStatus healthStatus = HealthStatus.UNKNOWN;
    @Column(nullable = false) private int consecutiveFailures;
    @Column(nullable = false) private int failureThreshold = 3;
    private Instant lastCheckedAt;
    private Instant lastSuccessAt;
    private Instant archivedAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected RuntimeEndpoint() { }

    public RuntimeEndpoint(UUID nodeId, RuntimeType runtimeType, String baseUrl, String apiToken) {
        this.nodeId = nodeId;
        this.runtimeType = runtimeType;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.encryptedApiToken = apiToken;
    }

    private static String stripTrailingSlash(String value) { return value.replaceAll("/+$", ""); }

    public void configure(String baseUrl, String encryptedApiToken, boolean replaceApiToken, boolean clearApiToken, Boolean enabled) {
        if (baseUrl != null && !baseUrl.isBlank()) this.baseUrl = stripTrailingSlash(baseUrl);
        if (clearApiToken) this.encryptedApiToken = null;
        else if (replaceApiToken) this.encryptedApiToken = encryptedApiToken;
        if (enabled != null) this.enabled = enabled;
    }

    /**
     * Historical requests, attempts, incidents, and audit records refer to this endpoint.
     * Archive it instead of deleting those operational records.
     */
    public void archive() {
        enabled = false;
        archivedAt = Instant.now();
    }

    public void recordHealth(boolean healthy) {
        lastCheckedAt = Instant.now();
        if (!healthy) {
            consecutiveFailures++;
            if (healthStatus != HealthStatus.DRAINING) {
                healthStatus = consecutiveFailures >= failureThreshold ? HealthStatus.UNHEALTHY : HealthStatus.SUSPECT;
            }
            return;
        }
        lastSuccessAt = lastCheckedAt;
        consecutiveFailures = 0;
        if (healthStatus == HealthStatus.DRAINING || healthStatus == HealthStatus.RECOVERING) return;
        healthStatus = healthStatus == HealthStatus.UNHEALTHY ? HealthStatus.RECOVERING : HealthStatus.HEALTHY;
    }

    public void beginDraining() { healthStatus = HealthStatus.DRAINING; }
    public void beginRecovery() { if (enabled) healthStatus = HealthStatus.RECOVERING; }
    public void completeRecovery() {
        if (enabled && healthStatus == HealthStatus.RECOVERING) {
            healthStatus = HealthStatus.HEALTHY;
            consecutiveFailures = 0;
            lastSuccessAt = Instant.now();
        }
    }
    public void failRecovery() {
        consecutiveFailures = Math.max(failureThreshold, consecutiveFailures + 1);
        healthStatus = HealthStatus.UNHEALTHY;
        lastCheckedAt = Instant.now();
    }

    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public RuntimeType getRuntimeType() { return runtimeType; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiToken() { return encryptedApiToken; }
    public boolean isEnabled() { return enabled; }
    public HealthStatus getHealthStatus() { return healthStatus; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public int getFailureThreshold() { return failureThreshold; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getArchivedAt() { return archivedAt; }
}
