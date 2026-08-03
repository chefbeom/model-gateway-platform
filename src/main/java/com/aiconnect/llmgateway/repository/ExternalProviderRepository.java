package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.ExternalProviderType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ExternalProviderRepository extends JpaRepository<ExternalProvider, UUID> {
    List<ExternalProvider> findByOrganizationIdOrderByDisplayNameAsc(UUID organizationId);
    List<ExternalProvider> findByOrganizationIdAndEnabledTrueOrderByDisplayNameAsc(UUID organizationId);
    boolean existsByOrganizationIdAndDisplayNameIgnoreCase(UUID organizationId, String displayName);
    boolean existsByOrganizationIdAndProviderTypeAndBaseUrl(UUID organizationId, ExternalProviderType providerType, String baseUrl);
}
