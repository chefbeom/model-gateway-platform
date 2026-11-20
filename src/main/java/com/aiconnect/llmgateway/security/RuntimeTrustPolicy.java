package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Fail closed until an endpoint is explicitly classified and approved. */
@Service
public class RuntimeTrustPolicy {
    private final RuntimeTrustAttestationRepository attestations;
    private final ProjectRuntimeApprovalRepository approvals;
    public RuntimeTrustPolicy(RuntimeTrustAttestationRepository attestations, ProjectRuntimeApprovalRepository approvals) {
        this.attestations = attestations; this.approvals = approvals;
    }

    public String denialReason(RuntimeEndpoint endpoint, Instant now) {
        var entry = attestations.findById(endpoint.getId()).orElse(null);
        if (entry == null) return "RUNTIME_TRUST_UNCLASSIFIED";
        if (!entry.validFor(endpoint.getBaseUrl(), now)) return "RUNTIME_TRUST_EXPIRED_OR_REVOKED";
        return entry.getClassification() == RuntimeTrustAttestation.Classification.INTERNAL
                ? null : "EXTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED";
    }
    public String denialReason(RuntimeEndpoint endpoint, Instant now, UUID projectId, boolean externalAllowed, boolean failover) {
        var entry = attestations.findById(endpoint.getId()).orElse(null);
        if (entry == null) return "RUNTIME_TRUST_UNCLASSIFIED";
        if (!entry.validFor(endpoint.getBaseUrl(), now)) return "RUNTIME_TRUST_EXPIRED_OR_REVOKED";
        if (entry.getClassification() == RuntimeTrustAttestation.Classification.INTERNAL) {
            if (projectId == null) return "INTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED";
            boolean allowed = approvals.findByProjectIdAndEndpointId(projectId, endpoint.getId())
                    .map(approval -> approval.allows(entry, now, false)).orElse(false);
            return allowed ? null : "INTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED";
        }
        if (!externalAllowed) return "DATA_PROTECTION_EXTERNAL_BLOCKED";
        if (entry.getClassification() != RuntimeTrustAttestation.Classification.EXTERNAL_NO_RETENTION || projectId == null)
            return "EXTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED";
        boolean allowed = approvals.findByProjectIdAndEndpointId(projectId, endpoint.getId())
                .map(approval -> approval.allows(entry, now, failover)).orElse(false);
        return allowed ? null : "EXTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED";
    }
    public boolean isInternal(RuntimeEndpoint endpoint, Instant now) {
        return attestations.findById(endpoint.getId()).filter(a -> a.validFor(endpoint.getBaseUrl(), now))
                .map(a -> a.getClassification() == RuntimeTrustAttestation.Classification.INTERNAL).orElse(false);
    }
    public boolean isVerifiedExternal(RuntimeEndpoint endpoint, Instant now) {
        return attestations.findById(endpoint.getId()).filter(a -> a.validFor(endpoint.getBaseUrl(), now))
                .map(a -> a.getClassification() != RuntimeTrustAttestation.Classification.INTERNAL).orElse(false);
    }
}
