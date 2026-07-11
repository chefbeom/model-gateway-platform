package com.aiconnect.llmgateway.retention;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ProjectContentPolicyRepository extends JpaRepository<ProjectContentPolicy, UUID> { }
