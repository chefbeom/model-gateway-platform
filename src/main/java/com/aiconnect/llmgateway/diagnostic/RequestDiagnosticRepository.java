package com.aiconnect.llmgateway.diagnostic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RequestDiagnosticRepository extends JpaRepository<RequestDiagnostic, UUID> { }
