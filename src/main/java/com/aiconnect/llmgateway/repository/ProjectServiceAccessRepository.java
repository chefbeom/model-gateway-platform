package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.ProjectServiceAccess;
import com.aiconnect.llmgateway.domain.ProjectServiceAccessId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProjectServiceAccessRepository extends JpaRepository<ProjectServiceAccess, ProjectServiceAccessId> {
    boolean existsByIdProjectIdAndIdServiceId(UUID projectId, UUID serviceId);
    List<ProjectServiceAccess> findByIdProjectId(UUID projectId);
}
