package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.PlaygroundRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaygroundRequestRepository extends JpaRepository<PlaygroundRequest, UUID> {
    List<PlaygroundRequest> findTop50ByOrganizationIdOrderByStartedAtDesc(UUID organizationId);
}
