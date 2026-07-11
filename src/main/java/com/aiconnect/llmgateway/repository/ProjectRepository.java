package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByOrganizationId(UUID organizationId);
}
