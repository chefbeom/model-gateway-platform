package com.aiconnect.llmgateway.quota;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpendQuotaRepository extends JpaRepository<SpendQuota, UUID> {
    List<SpendQuota> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);
    List<SpendQuota> findByOrganizationIdAndEnabledTrue(UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select quota from SpendQuota quota where quota.id = :id")
    Optional<SpendQuota> findByIdForUpdate(@Param("id") UUID id);
}
