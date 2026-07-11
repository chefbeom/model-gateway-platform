package com.aiconnect.llmgateway.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {
    List<Team> findByOrganizationIdOrderByNameAsc(UUID organizationId);
}
