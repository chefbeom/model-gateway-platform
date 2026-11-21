package com.aiconnect.llmgateway.beta;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
public interface BetaServiceRepository extends JpaRepository<BetaService,UUID> {
    List<BetaService> findByOrganizationId(UUID organizationId);
}
