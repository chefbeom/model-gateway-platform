package com.aiconnect.llmgateway.dataprotection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A data-protection policy is deliberately independent from routing and pricing
 * records.  This lets an administrator apply a privacy boundary to one project
 * or credential without changing the logical model itself.
 */
@Entity
@Table(name = "data_protection_policy")
public class DataProtectionPolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(columnDefinition = "char(36)")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DataProtectionScopeType scopeType;

    @Column(columnDefinition = "char(36)")
    private UUID scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DataProtectionMode mode = DataProtectionMode.OFF;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DataProtectionLevel level = DataProtectionLevel.RELAXED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DataProtectionAction externalAction = DataProtectionAction.ALLOW;

    @Column(nullable = false)
    private boolean allowExternalFailover = true;

    @Column(nullable = false)
    private boolean detectSecrets = true;

    @Column(nullable = false)
    private boolean detectPii;

    @Column(nullable = false)
    private boolean detectFinancial;

    @Column(nullable = false)
    private boolean detectConfidential;

    @Column(nullable = false)
    private boolean detectMedia = true;

    @Column(columnDefinition = "text")
    private String customPatternsJson = "[]";

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected DataProtectionPolicy() { }

    public DataProtectionPolicy(UUID organizationId, DataProtectionScopeType scopeType, UUID scopeId,
                               DataProtectionMode mode, DataProtectionLevel level,
                               DataProtectionAction externalAction, boolean allowExternalFailover,
                               boolean detectSecrets, boolean detectPii, boolean detectFinancial,
                               boolean detectConfidential, boolean detectMedia, String customPatternsJson) {
        this.organizationId = organizationId;
        this.scopeType = scopeType == null ? DataProtectionScopeType.ORGANIZATION : scopeType;
        this.scopeId = scopeId;
        configure(mode, level, externalAction, allowExternalFailover, detectSecrets, detectPii,
                detectFinancial, detectConfidential, detectMedia, customPatternsJson);
    }

    public void configure(DataProtectionMode mode, DataProtectionLevel level,
                          DataProtectionAction externalAction, Boolean allowExternalFailover,
                          Boolean detectSecrets, Boolean detectPii, Boolean detectFinancial,
                          Boolean detectConfidential, Boolean detectMedia, String customPatternsJson) {
        if (mode != null) this.mode = mode;
        if (level != null) this.level = level;
        if (externalAction != null) this.externalAction = externalAction;
        if (allowExternalFailover != null) this.allowExternalFailover = allowExternalFailover;
        if (detectSecrets != null) this.detectSecrets = detectSecrets;
        if (detectPii != null) this.detectPii = detectPii;
        if (detectFinancial != null) this.detectFinancial = detectFinancial;
        if (detectConfidential != null) this.detectConfidential = detectConfidential;
        if (detectMedia != null) this.detectMedia = detectMedia;
        if (customPatternsJson != null) this.customPatternsJson = customPatternsJson;
    }

    @PreUpdate
    void updateTimestamp() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public DataProtectionScopeType getScopeType() { return scopeType; }
    public UUID getScopeId() { return scopeId; }
    public DataProtectionMode getMode() { return mode; }
    public DataProtectionLevel getLevel() { return level; }
    public DataProtectionAction getExternalAction() { return externalAction; }
    public boolean isAllowExternalFailover() { return allowExternalFailover; }
    public boolean isDetectSecrets() { return detectSecrets; }
    public boolean isDetectPii() { return detectPii; }
    public boolean isDetectFinancial() { return detectFinancial; }
    public boolean isDetectConfidential() { return detectConfidential; }
    public boolean isDetectMedia() { return detectMedia; }
    public String getCustomPatternsJson() { return customPatternsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
