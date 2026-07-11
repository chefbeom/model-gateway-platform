package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_service")
public class LlmService {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID organizationId;
    @Column(nullable = false, length = 120) private String serviceKey;
    @Column(nullable = false, length = 200) private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private FailoverPolicy failoverPolicy = FailoverPolicy.STRICT;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private RetryPolicy retryPolicy = RetryPolicy.SAFE;
    @Column(nullable = false) private boolean allowDegraded;
    @Column(columnDefinition = "text") private String requiredCapabilitiesJson = "[]";
    @Column(nullable = false, precision = 18, scale = 6) private BigDecimal inputPricePerMillion = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 6) private BigDecimal outputPricePerMillion = BigDecimal.ZERO;
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected LlmService() { }

    public LlmService(UUID organizationId, String serviceKey, String displayName, FailoverPolicy failoverPolicy,
                      boolean allowDegraded, String requiredCapabilitiesJson, BigDecimal inputPricePerMillion,
                      BigDecimal outputPricePerMillion) {
        this(organizationId, serviceKey, displayName, failoverPolicy, RetryPolicy.SAFE, allowDegraded,
                requiredCapabilitiesJson, inputPricePerMillion, outputPricePerMillion);
    }

    public LlmService(UUID organizationId, String serviceKey, String displayName, FailoverPolicy failoverPolicy,
                      RetryPolicy retryPolicy, boolean allowDegraded, String requiredCapabilitiesJson,
                      BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
        this.organizationId = organizationId;
        this.serviceKey = serviceKey;
        this.displayName = displayName;
        this.failoverPolicy = failoverPolicy == null ? FailoverPolicy.STRICT : failoverPolicy;
        this.retryPolicy = retryPolicy == null ? RetryPolicy.SAFE : retryPolicy;
        this.allowDegraded = allowDegraded;
        this.requiredCapabilitiesJson = requiredCapabilitiesJson == null ? "[]" : requiredCapabilitiesJson;
        this.inputPricePerMillion = inputPricePerMillion == null ? BigDecimal.ZERO : inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion == null ? BigDecimal.ZERO : outputPricePerMillion;
    }

    public void configure(String displayName, FailoverPolicy failoverPolicy, RetryPolicy retryPolicy,
                          Boolean allowDegraded, String requiredCapabilitiesJson,
                          BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, Boolean enabled) {
        if (displayName != null && !displayName.isBlank()) this.displayName = displayName;
        if (failoverPolicy != null) this.failoverPolicy = failoverPolicy;
        if (retryPolicy != null) this.retryPolicy = retryPolicy;
        if (allowDegraded != null) this.allowDegraded = allowDegraded;
        if (requiredCapabilitiesJson != null) this.requiredCapabilitiesJson = requiredCapabilitiesJson;
        if (inputPricePerMillion != null) this.inputPricePerMillion = inputPricePerMillion;
        if (outputPricePerMillion != null) this.outputPricePerMillion = outputPricePerMillion;
        if (enabled != null) this.enabled = enabled;
    }

    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getServiceKey() { return serviceKey; }
    public String getDisplayName() { return displayName; }
    public FailoverPolicy getFailoverPolicy() { return failoverPolicy; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public boolean isAllowDegraded() { return allowDegraded; }
    public String getRequiredCapabilitiesJson() { return requiredCapabilitiesJson; }
    public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; }
    public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; }
    public boolean isEnabled() { return enabled; }
}
