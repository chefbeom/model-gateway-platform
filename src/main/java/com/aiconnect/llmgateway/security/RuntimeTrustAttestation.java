package com.aiconnect.llmgateway.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "runtime_trust_attestation")
public class RuntimeTrustAttestation {
    public enum Classification { INTERNAL, EXTERNAL_UNVERIFIED, EXTERNAL_NO_RETENTION }

    @Id @Column(name = "endpoint_id", columnDefinition = "char(36)") private UUID endpointId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private Classification classification;
    @Column(nullable = false, length = 500) private String endpointUrl;
    @Column(nullable = false, length = 1000) private String evidence;
    private Instant expiresAt;
    @Column(columnDefinition = "char(36)") private UUID approvedBy;
    @Column(nullable = false) private Instant decidedAt;
    private Instant revokedAt;
    @Column(name = "vendor_name", length = 120) private String vendorName;
    @Column(name = "processing_location", length = 120) private String processingLocation;
    @Column(name = "retention_policy", length = 240) private String retentionPolicy;
    @Column(name = "approval_scope", length = 240) private String approvalScope;

    protected RuntimeTrustAttestation() { }

    public RuntimeTrustAttestation(UUID endpointId, Classification classification, String endpointUrl,
                                   String evidence, Instant expiresAt, UUID approvedBy, Instant decidedAt) {
        this(endpointId, classification, endpointUrl, evidence, expiresAt, approvedBy, decidedAt,
                null, null, null, null);
    }

    public RuntimeTrustAttestation(UUID endpointId, Classification classification, String endpointUrl,
                                   String evidence, Instant expiresAt, UUID approvedBy, Instant decidedAt,
                                   String vendorName, String processingLocation,
                                   String retentionPolicy, String approvalScope) {
        this.endpointId = endpointId;
        this.classification = classification;
        this.endpointUrl = endpointUrl;
        this.evidence = evidence;
        this.expiresAt = expiresAt;
        this.approvedBy = approvedBy;
        this.decidedAt = decidedAt;
        this.vendorName = trimOrNull(vendorName);
        this.processingLocation = trimOrNull(processingLocation);
        this.retentionPolicy = trimOrNull(retentionPolicy);
        this.approvalScope = trimOrNull(approvalScope);
    }

    public boolean validFor(String currentUrl, Instant now) {
        return revokedAt == null && endpointUrl.equals(currentUrl) && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean hasRequiredExternalNoRetentionMetadata() {
        return hasText(vendorName) && hasText(processingLocation)
                && hasText(retentionPolicy) && hasText(approvalScope);
    }

    public void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }
    public UUID getEndpointId() { return endpointId; }
    public Classification getClassification() { return classification; }
    public String getEndpointUrl() { return endpointUrl; }
    public String getEvidence() { return evidence; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getVendorName() { return vendorName; }
    public String getProcessingLocation() { return processingLocation; }
    public String getRetentionPolicy() { return retentionPolicy; }
    public String getApprovalScope() { return approvalScope; }

    private static String trimOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
