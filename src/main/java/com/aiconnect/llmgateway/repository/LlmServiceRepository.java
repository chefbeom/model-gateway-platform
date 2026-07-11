package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.LlmService;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LlmServiceRepository extends JpaRepository<LlmService, UUID> {
    Optional<LlmService> findByOrganizationIdAndServiceKeyAndEnabledTrue(UUID organizationId, String serviceKey);
    List<LlmService> findByOrganizationIdAndEnabledTrue(UUID organizationId);
    List<LlmService> findByOrganizationIdOrderByServiceKeyAsc(UUID organizationId);
}
