package com.aiconnect.llmgateway.beta;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface BetaServiceRepository extends JpaRepository<BetaService,UUID> {}
