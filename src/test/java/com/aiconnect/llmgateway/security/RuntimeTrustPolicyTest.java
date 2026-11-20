package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RuntimeTrustPolicyTest {
    @Test void rejectsUnknownExternalAndExpiredUntilProjectApprovalExists() {
        var repository = mock(RuntimeTrustAttestationRepository.class);
        var policy = new RuntimeTrustPolicy(repository, mock(ProjectRuntimeApprovalRepository.class));
        var endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LLAMA_CPP, "http://100.106.74.36:4040", null);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(endpoint, "id", id);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThat(policy.denialReason(endpoint, now)).isEqualTo("RUNTIME_TRUST_UNCLASSIFIED");
        var external = new RuntimeTrustAttestation(id, RuntimeTrustAttestation.Classification.EXTERNAL_NO_RETENTION,
                endpoint.getBaseUrl(), "reviewed", now.plusSeconds(30), UUID.randomUUID(), now);
        when(repository.findById(id)).thenReturn(Optional.of(external));
        assertThat(policy.denialReason(endpoint, now, UUID.randomUUID(), true, false)).isEqualTo("EXTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED");
        assertThat(policy.denialReason(endpoint, now.plusSeconds(30))).isEqualTo("RUNTIME_TRUST_EXPIRED_OR_REVOKED");
        var internal = new RuntimeTrustAttestation(id, RuntimeTrustAttestation.Classification.INTERNAL,
                endpoint.getBaseUrl(), "reviewed", null, UUID.randomUUID(), now);
        when(repository.findById(id)).thenReturn(Optional.of(internal));
        assertThat(policy.denialReason(endpoint, now)).isNull();
        assertThat(policy.denialReason(endpoint, now, UUID.randomUUID(), false, false)).isEqualTo("INTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED");
        internal.revoke(now);
        assertThat(policy.denialReason(endpoint, now)).isEqualTo("RUNTIME_TRUST_EXPIRED_OR_REVOKED");
    }

    @Test void externalRuntimeRequiresMatchingProjectApprovalAndExplicitFailover() {
        var attestations = mock(RuntimeTrustAttestationRepository.class);
        var approvals = mock(ProjectRuntimeApprovalRepository.class);
        var policy = new RuntimeTrustPolicy(attestations, approvals);
        var endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LLAMA_CPP, "http://100.106.74.36:4040", null);
        UUID endpointId = UUID.randomUUID(), projectId = UUID.randomUUID();
        ReflectionTestUtils.setField(endpoint, "id", endpointId);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var trust = new RuntimeTrustAttestation(endpointId, RuntimeTrustAttestation.Classification.EXTERNAL_NO_RETENTION,
                endpoint.getBaseUrl(), "reviewed", now.plusSeconds(100), UUID.randomUUID(), now);
        when(attestations.findById(endpointId)).thenReturn(Optional.of(trust));
        var approval = new ProjectRuntimeApproval(projectId, endpointId);
        approval.approve(now, true, false, now.plusSeconds(50), UUID.randomUUID());
        when(approvals.findByProjectIdAndEndpointId(projectId, endpointId)).thenReturn(Optional.of(approval));
        assertThat(policy.denialReason(endpoint, now, projectId, true, false)).isNull();
        assertThat(policy.denialReason(endpoint, now, projectId, true, true)).isEqualTo("EXTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED");
        assertThat(policy.denialReason(endpoint, now, projectId, false, false)).isEqualTo("DATA_PROTECTION_EXTERNAL_BLOCKED");
        assertThat(policy.denialReason(endpoint, now.plusSeconds(50), projectId, true, false)).isEqualTo("EXTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED");
        assertThat(policy.denialReason(endpoint, now, UUID.randomUUID(), true, false)).isEqualTo("EXTERNAL_RUNTIME_PROJECT_APPROVAL_REQUIRED");
        trust.revoke(now);
        assertThat(policy.denialReason(endpoint, now, projectId, true, false)).isEqualTo("RUNTIME_TRUST_EXPIRED_OR_REVOKED");
    }
}
