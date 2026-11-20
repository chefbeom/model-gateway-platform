package com.aiconnect.llmgateway.security;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuntimeTrustAttestationRepository extends JpaRepository<RuntimeTrustAttestation, UUID> { }
