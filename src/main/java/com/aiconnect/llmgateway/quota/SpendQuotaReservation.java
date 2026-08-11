package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.domain.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A short-lived pre-authorization that prevents concurrent requests from overspending a quota. */
@Entity
@Table(name = "spend_quota_reservation", indexes = {
        @Index(name = "idx_spend_quota_reservation_quota_expiry", columnList = "quota_id,expires_at"),
        @Index(name = "idx_spend_quota_reservation_key", columnList = "reservation_key")
})
public class SpendQuotaReservation {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID quotaId;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID reservationKey;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant expiresAt;

    protected SpendQuotaReservation() { }

    public SpendQuotaReservation(UUID quotaId, UUID reservationKey, BigDecimal amount, Currency currency, Instant expiresAt) {
        this.quotaId = quotaId;
        this.reservationKey = reservationKey;
        this.amount = amount;
        this.currency = currency == null ? Currency.KRW : currency;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getQuotaId() { return quotaId; }
    public UUID getReservationKey() { return reservationKey; }
    public BigDecimal getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
