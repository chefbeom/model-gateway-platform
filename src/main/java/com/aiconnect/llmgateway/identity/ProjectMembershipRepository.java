package com.aiconnect.llmgateway.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProjectMembershipRepository extends JpaRepository<ProjectMembership, ProjectMembershipId> {
    List<ProjectMembership> findByIdUserId(UUID userId);
    List<ProjectMembership> findByIdProjectId(UUID projectId);
    List<ProjectMembership> findByIdProjectIdAndRole(UUID projectId, ProjectMembership.Role role);
    boolean existsByIdProjectIdAndIdUserIdAndRole(UUID projectId, UUID userId, ProjectMembership.Role role);
    long countByIdUserId(UUID userId);
}
