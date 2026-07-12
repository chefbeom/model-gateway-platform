package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_external_access")
public class ProjectExternalAccess {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID projectId;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID providerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private ExternalAccessStatus status = ExternalAccessStatus.REQUESTED;
    @Column(columnDefinition = "char(36)") private UUID requestedByUserId;
    @Column(nullable = false, length = 1000) private String requestedReason;
    @Column(nullable = false) private boolean manualAllowed;
    @Column(nullable = false) private boolean autoFailoverEnabled;
    @Column(precision = 18, scale = 6) private BigDecimal monthlyCostLimit;
    private Instant expiresAt;
    @Column(columnDefinition = "char(36)") private UUID approvedByUserId;
    private Instant decidedAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected ProjectExternalAccess() { }

    public ProjectExternalAccess(UUID projectId, UUID providerId, UUID requestedByUserId, String requestedReason) {
        this.projectId = projectId;
        this.providerId = providerId;
        request(requestedByUserId, requestedReason);
    }

    public void request(UUID userId, String reason) {
        requestedByUserId = userId;
        requestedReason = reason == null || reason.isBlank() ? "외부 AI 사용이 필요합니다." : reason.trim();
        status = ExternalAccessStatus.REQUESTED;
        manualAllowed = false;
        autoFailoverEnabled = false;
        approvedByUserId = null;
        decidedAt = null;
    }

    public void decide(ExternalAccessStatus status, boolean manualAllowed, boolean autoFailoverEnabled,
                       BigDecimal monthlyCostLimit, Instant expiresAt, UUID actorId) {
        if (status == ExternalAccessStatus.REQUESTED) throw new IllegalArgumentException("A decision status is required.");
        this.status = status;
        boolean approved = status == ExternalAccessStatus.APPROVED;
        this.manualAllowed = approved && manualAllowed;
        this.autoFailoverEnabled = approved && autoFailoverEnabled;
        this.monthlyCostLimit = approved ? monthlyCostLimit : null;
        this.expiresAt = approved ? expiresAt : null;
        this.approvedByUserId = actorId;
        this.decidedAt = Instant.now();
    }

    public boolean isActive() {
        return status == ExternalAccessStatus.APPROVED && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }

    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getProviderId() { return providerId; }
    public ExternalAccessStatus getStatus() { return status; }
    public UUID getRequestedByUserId() { return requestedByUserId; }
    public String getRequestedReason() { return requestedReason; }
    public boolean isManualAllowed() { return manualAllowed; }
    public boolean isAutoFailoverEnabled() { return autoFailoverEnabled; }
    public BigDecimal getMonthlyCostLimit() { return monthlyCostLimit; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getApprovedByUserId() { return approvedByUserId; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
