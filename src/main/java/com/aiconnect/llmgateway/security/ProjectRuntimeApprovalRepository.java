package com.aiconnect.llmgateway.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface ProjectRuntimeApprovalRepository extends JpaRepository<ProjectRuntimeApproval, UUID> {
    Optional<ProjectRuntimeApproval> findByProjectIdAndEndpointId(UUID projectId, UUID endpointId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ProjectRuntimeApproval a where a.projectId = :projectId and a.endpointId = :endpointId")
    Optional<ProjectRuntimeApproval> findForUpdate(@Param("projectId") UUID projectId, @Param("endpointId") UUID endpointId);
}
