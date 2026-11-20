package com.aiconnect.llmgateway.security;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeTrustAttestationTest {
    @Test void approvalExpiresAndIsBoundToExactEndpoint() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var attestation = new RuntimeTrustAttestation(UUID.randomUUID(),
                RuntimeTrustAttestation.Classification.EXTERNAL_NO_RETENTION,
                "http://100.106.74.36:4040", "DPA reviewed", now.plusSeconds(60), UUID.randomUUID(), now);
        assertThat(attestation.validFor("http://100.106.74.36:4040", now)).isTrue();
        assertThat(attestation.validFor("http://100.106.74.36:4041", now)).isFalse();
        assertThat(attestation.validFor("http://100.106.74.36:4040", now.plusSeconds(60))).isFalse();
        assertThat(attestation.hasRequiredExternalNoRetentionMetadata()).isFalse();
        attestation.revoke(now);
        assertThat(attestation.validFor("http://100.106.74.36:4040", now)).isFalse();
    }

    @Test void storesExternalNonRetentionMetadataAndTracksExplicitRevoke() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var attestation = new RuntimeTrustAttestation(UUID.randomUUID(),
                RuntimeTrustAttestation.Classification.EXTERNAL_NO_RETENTION,
                "https://gpu.vendor.example:8443", "Signed DPA", now.plusSeconds(3600),
                UUID.randomUUID(), now,
                "CloudGPU Corp", "Seoul, KR", "ZERO_DATA_RETENTION", "Document inference only");
        assertThat(attestation.hasRequiredExternalNoRetentionMetadata()).isTrue();
        assertThat(attestation.getVendorName()).isEqualTo("CloudGPU Corp");
        assertThat(attestation.getProcessingLocation()).isEqualTo("Seoul, KR");
        assertThat(attestation.getRetentionPolicy()).isEqualTo("ZERO_DATA_RETENTION");
        assertThat(attestation.getApprovalScope()).isEqualTo("Document inference only");
        assertThat(attestation.getRevokedAt()).isNull();
        attestation.revoke(now.plusSeconds(10));
        assertThat(attestation.getRevokedAt()).isEqualTo(now.plusSeconds(10));
        assertThat(attestation.validFor("https://gpu.vendor.example:8443", now.plusSeconds(20))).isFalse();
    }
}
