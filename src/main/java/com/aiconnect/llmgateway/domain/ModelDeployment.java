package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "model_deployment")
public class ModelDeployment {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(columnDefinition = "char(36)") private UUID runtimeEndpointId;
    @Column(columnDefinition = "char(36)") private UUID externalProviderId;
    @Column(nullable = false, length = 500) private String providerModelId;
    @Column(nullable = false, length = 500) private String compatibilityKey;
    @Column(nullable = false, length = 200) private String displayName;
    @Column(length = 120) private String modelFamily;
    @Column(length = 60) private String quantization;
    private Integer contextLength;
    @Column(nullable = false) private boolean loaded;
    @Column(nullable = false) private boolean enabled = true;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private HealthStatus healthStatus = HealthStatus.UNKNOWN;
    @Column(nullable = false) private int maxConcurrency = 1;
    @Column(precision = 18, scale = 6) private java.math.BigDecimal providerInputPricePerMillion;
    @Column(precision = 18, scale = 6) private java.math.BigDecimal providerOutputPricePerMillion;
    @Column(columnDefinition = "text") private String capabilitiesJson = "[]";
    @Column(columnDefinition = "text") private String capabilityOverridesJson;
    @Column(columnDefinition = "text") private String metadataJson;
    private Instant lastSyncedAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected ModelDeployment() { }

    public ModelDeployment(UUID runtimeEndpointId, String providerModelId, String displayName, String modelFamily,
                           String quantization, Integer contextLength, boolean loaded, int maxConcurrency, String capabilitiesJson) {
        this(runtimeEndpointId, providerModelId, providerModelId, displayName, modelFamily, quantization,
                contextLength, loaded, maxConcurrency, capabilitiesJson);
    }

    public ModelDeployment(UUID runtimeEndpointId, String providerModelId, String compatibilityKey, String displayName,
                           String modelFamily, String quantization, Integer contextLength, boolean loaded,
                           int maxConcurrency, String capabilitiesJson) {
        this.runtimeEndpointId = runtimeEndpointId;
        this.providerModelId = providerModelId;
        this.compatibilityKey = compatibilityKey == null || compatibilityKey.isBlank() ? providerModelId : compatibilityKey;
        this.displayName = displayName;
        synchronize(displayName, modelFamily, quantization, contextLength, loaded, maxConcurrency, capabilitiesJson, null);
    }

    public static ModelDeployment external(UUID providerId, String providerModelId, String compatibilityKey,
                                           String displayName, Integer contextLength, int maxConcurrency,
                                           String capabilitiesJson, java.math.BigDecimal inputPrice,
                                           java.math.BigDecimal outputPrice) {
        ModelDeployment deployment = new ModelDeployment();
        deployment.externalProviderId = providerId;
        deployment.providerModelId = providerModelId;
        deployment.compatibilityKey = compatibilityKey == null || compatibilityKey.isBlank() ? providerModelId : compatibilityKey;
        deployment.displayName = displayName == null || displayName.isBlank() ? providerModelId : displayName;
        deployment.contextLength = contextLength;
        deployment.loaded = true;
        deployment.enabled = true;
        deployment.healthStatus = HealthStatus.HEALTHY;
        deployment.maxConcurrency = Math.max(1, maxConcurrency);
        deployment.capabilitiesJson = capabilitiesJson == null ? "[]" : capabilitiesJson;
        deployment.providerInputPricePerMillion = inputPrice;
        deployment.providerOutputPricePerMillion = outputPrice;
        deployment.lastSyncedAt = Instant.now();
        return deployment;
    }

    public void synchronize(String displayName, String modelFamily, String quantization, Integer contextLength,
                            boolean loaded, int maxConcurrency, String capabilitiesJson, String metadataJson) {
        this.displayName = displayName == null || displayName.isBlank() ? providerModelId : displayName;
        this.modelFamily = modelFamily;
        this.quantization = quantization;
        this.contextLength = contextLength;
        this.loaded = loaded;
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.capabilitiesJson = capabilitiesJson == null ? "[]" : capabilitiesJson;
        this.metadataJson = metadataJson;
        this.healthStatus = loaded ? HealthStatus.HEALTHY : HealthStatus.UNKNOWN;
        this.lastSyncedAt = Instant.now();
    }

    public void configure(String compatibilityKey, Boolean enabled, Integer maxConcurrency,
                          String capabilityOverridesJson) {
        if (compatibilityKey != null && !compatibilityKey.isBlank()) this.compatibilityKey = compatibilityKey;
        if (enabled != null) this.enabled = enabled;
        if (maxConcurrency != null) this.maxConcurrency = Math.max(1, maxConcurrency);
        if (capabilityOverridesJson != null) this.capabilityOverridesJson = capabilityOverridesJson;
    }

    public void configureProviderModel(String displayName, String compatibilityKey, Boolean enabled,
                                       Integer maxConcurrency, String capabilitiesJson,
                                       java.math.BigDecimal inputPrice, java.math.BigDecimal outputPrice) {
        if (displayName != null && !displayName.isBlank()) this.displayName = displayName;
        if (compatibilityKey != null && !compatibilityKey.isBlank()) this.compatibilityKey = compatibilityKey;
        if (enabled != null) this.enabled = enabled;
        if (maxConcurrency != null) this.maxConcurrency = Math.max(1, maxConcurrency);
        if (capabilitiesJson != null) this.capabilitiesJson = capabilitiesJson;
        if (inputPrice != null) this.providerInputPricePerMillion = inputPrice;
        if (outputPrice != null) this.providerOutputPricePerMillion = outputPrice;
        this.loaded = true;
        this.healthStatus = HealthStatus.HEALTHY;
        this.lastSyncedAt = Instant.now();
    }

    public void markUnavailable() {
        loaded = false;
        healthStatus = HealthStatus.UNHEALTHY;
        lastSyncedAt = Instant.now();
    }

    public void recordHealth(boolean healthy) { healthStatus = healthy ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY; }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getRuntimeEndpointId() { return runtimeEndpointId; }
    public UUID getExternalProviderId() { return externalProviderId; }
    public boolean isExternal() { return externalProviderId != null; }
    public String getProviderModelId() { return providerModelId; }
    public String getCompatibilityKey() { return compatibilityKey; }
    public String getDisplayName() { return displayName; }
    public String getModelFamily() { return modelFamily; }
    public String getQuantization() { return quantization; }
    public Integer getContextLength() { return contextLength; }
    public boolean isLoaded() { return loaded; }
    public boolean isEnabled() { return enabled; }
    public HealthStatus getHealthStatus() { return healthStatus; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public java.math.BigDecimal getProviderInputPricePerMillion() { return providerInputPricePerMillion; }
    public java.math.BigDecimal getProviderOutputPricePerMillion() { return providerOutputPricePerMillion; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public String getCapabilityOverridesJson() { return capabilityOverridesJson; }
    public String getMetadataJson() { return metadataJson; }
}
