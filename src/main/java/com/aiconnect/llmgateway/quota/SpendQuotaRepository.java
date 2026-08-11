package com.aiconnect.llmgateway.quota;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpendQuotaRepository extends JpaRepository<SpendQuota, UUID> {
    List<SpendQuota> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);
    List<SpendQuota> findByOrganizationIdAndEnabledTrue(UUID organizationId);
}
