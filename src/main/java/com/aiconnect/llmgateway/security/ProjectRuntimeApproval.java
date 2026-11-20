package com.aiconnect.llmgateway.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_runtime_approval", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "endpoint_id"}))
public class ProjectRuntimeApproval {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID projectId;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID endpointId;
    @Column(nullable = false) private Instant attestationDecidedAt;
    @Column(nullable = false) private boolean manualAllowed;
    @Column(nullable = false) private boolean failoverAllowed;
    @Column(nullable = false) private Instant expiresAt;
    @Column(columnDefinition = "char(36)") private UUID approvedBy;
    protected ProjectRuntimeApproval() { }
    public ProjectRuntimeApproval(UUID projectId, UUID endpointId) { this.projectId = projectId; this.endpointId = endpointId; }
    public void approve(Instant attestationDecidedAt, boolean manualAllowed, boolean failoverAllowed, Instant expiresAt, UUID approvedBy) {
        this.attestationDecidedAt = attestationDecidedAt;
        this.manualAllowed = manualAllowed;
        this.failoverAllowed = failoverAllowed;
        this.expiresAt = expiresAt;
        this.approvedBy = approvedBy;
    }
    public boolean allows(RuntimeTrustAttestation attestation, Instant now, boolean failover) {
        return expiresAt.isAfter(now) && attestationDecidedAt.equals(attestation.getDecidedAt())
                && (failover ? failoverAllowed : manualAllowed);
    }
    public void revoke() { manualAllowed = false; failoverAllowed = false; }
    public boolean isManualAllowed() { return manualAllowed; }
    public boolean isFailoverAllowed() { return failoverAllowed; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getApprovedBy() { return approvedBy; }
}
