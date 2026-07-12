package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.ProjectExternalAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectExternalAccessRepository extends JpaRepository<ProjectExternalAccess, UUID> {
    Optional<ProjectExternalAccess> findByProjectIdAndProviderId(UUID projectId, UUID providerId);
    List<ProjectExternalAccess> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    List<ProjectExternalAccess> findByProjectIdInOrderByCreatedAtDesc(List<UUID> projectIds);
}
