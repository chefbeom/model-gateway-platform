package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.Currency;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A hard spend ceiling. The amount is expressed in the configured currency and
 * applies to the request cost snapshot stored on {@code llm_request}.
 */
@Entity
@Table(name = "spend_quota", indexes = {
        @Index(name = "idx_spend_quota_org_enabled", columnList = "organization_id,enabled"),
        @Index(name = "idx_spend_quota_scope", columnList = "scope_type,scope_id")
})
public class SpendQuota {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SpendQuotaScope scopeType;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID scopeId;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency = Currency.KRW;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal limitAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SpendQuotaPeriod period = SpendQuotaPeriod.MONTHLY;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected SpendQuota() { }

    public SpendQuota(UUID organizationId, SpendQuotaScope scopeType, UUID scopeId, String name,
                      Currency currency, BigDecimal limitAmount, SpendQuotaPeriod period, boolean enabled) {
        this.organizationId = organizationId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.name = name;
        this.currency = currency == null ? Currency.KRW : currency;
        this.limitAmount = limitAmount;
        this.period = period == null ? SpendQuotaPeriod.MONTHLY : period;
        this.enabled = enabled;
    }

    public void configure(SpendQuotaScope scopeType, UUID scopeId, String name, Currency currency,
                          BigDecimal limitAmount, SpendQuotaPeriod period, Boolean enabled) {
        if (scopeType != null) this.scopeType = scopeType;
        if (scopeId != null) this.scopeId = scopeId;
        if (name != null && !name.isBlank()) this.name = name;
        if (currency != null) this.currency = currency;
        if (limitAmount != null) this.limitAmount = limitAmount;
        if (period != null) this.period = period;
        if (enabled != null) this.enabled = enabled;
    }

    @PreUpdate
    void updateTimestamp() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public SpendQuotaScope getScopeType() { return scopeType; }
    public UUID getScopeId() { return scopeId; }
    public String getName() { return name; }
    public Currency getCurrency() { return currency == null ? Currency.KRW : currency; }
    public BigDecimal getLimitAmount() { return limitAmount; }
    public SpendQuotaPeriod getPeriod() { return period; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
