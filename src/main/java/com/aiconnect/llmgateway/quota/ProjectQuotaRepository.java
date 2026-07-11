package com.aiconnect.llmgateway.quota;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ProjectQuotaRepository extends JpaRepository<ProjectQuota, UUID> { }
