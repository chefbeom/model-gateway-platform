package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.LlmRequestAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface LlmRequestAttemptRepository extends JpaRepository<LlmRequestAttempt, UUID> { }
