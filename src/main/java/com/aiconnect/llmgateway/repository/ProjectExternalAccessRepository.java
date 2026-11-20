package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.ProjectExternalAccess;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectExternalAccessRepository extends JpaRepository<ProjectExternalAccess, UUID> {
    Optional<ProjectExternalAccess> findByProjectIdAndProviderId(UUID projectId, UUID providerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectExternalAccess p where p.projectId = :projectId and p.providerId = :providerId")
    Optional<ProjectExternalAccess> findForUpdate(@Param("projectId") UUID projectId, @Param("providerId") UUID providerId);
    List<ProjectExternalAccess> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    List<ProjectExternalAccess> findByProjectIdInOrderByCreatedAtDesc(List<UUID> projectIds);
    long countByProviderId(UUID providerId);
}
