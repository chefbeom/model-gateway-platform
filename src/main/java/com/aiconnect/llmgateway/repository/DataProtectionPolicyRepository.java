package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.dataprotection.DataProtectionPolicy;
import com.aiconnect.llmgateway.dataprotection.DataProtectionScopeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataProtectionPolicyRepository extends JpaRepository<DataProtectionPolicy, UUID> {
    Optional<DataProtectionPolicy> findByScopeTypeAndOrganizationIdAndScopeId(
            DataProtectionScopeType scopeType, UUID organizationId, UUID scopeId);

    Optional<DataProtectionPolicy> findByScopeTypeAndOrganizationIdIsNullAndScopeIdIsNull(
            DataProtectionScopeType scopeType);

    List<DataProtectionPolicy> findByOrganizationIdOrderByScopeTypeAsc(UUID organizationId);
}
