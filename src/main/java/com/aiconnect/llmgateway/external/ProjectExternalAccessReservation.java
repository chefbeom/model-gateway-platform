package com.aiconnect.llmgateway.external;

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

/** Atomic provider monthly cost cap reservation with 4-state lifecycle. */
@Entity
@Table(name = "project_external_access_reservation", indexes = {
        @Index(name = "idx_pear_proj_prov_status_exp", columnList = "project_id,provider_id,status,expires_at"),
        @Index(name = "idx_pear_key", columnList = "reservation_key")
})
public class ProjectExternalAccessReservation {
    public enum ReservationStatus {
        ACTIVE,
        RELEASED,
        SETTLED,
        HELD_UNSETTLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID projectId;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID providerId;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID reservationKey;

    @Column(length = 64)
    private String requestId;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReservationStatus status = ReservationStatus.ACTIVE;

    @Column(nullable = false)
    private Instant periodStart;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant settledAt;

    @Column(length = 120)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ProjectExternalAccessReservation() { }

    public ProjectExternalAccessReservation(UUID projectId, UUID providerId, UUID reservationKey, String requestId,
                                            BigDecimal amount, Currency currency, Instant periodStart, Instant expiresAt) {
        this.projectId = projectId;
        this.providerId = providerId;
        this.reservationKey = reservationKey;
        this.requestId = requestId;
        this.amount = amount;
        this.currency = currency;
        this.periodStart = periodStart;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getProviderId() { return providerId; }
    public UUID getReservationKey() { return reservationKey; }
    public String getRequestId() { return requestId; }
    public BigDecimal getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public ReservationStatus getStatus() { return status; }
    public Instant getPeriodStart() { return periodStart; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getSettledAt() { return settledAt; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
