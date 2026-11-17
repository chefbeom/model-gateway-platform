package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.ServiceTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceTargetRepository extends JpaRepository<ServiceTarget, UUID> {
    List<ServiceTarget> findByServiceIdAndEnabledTrueOrderByPriorityAsc(UUID serviceId);
    List<ServiceTarget> findByServiceIdOrderByPriorityAsc(UUID serviceId);
    List<ServiceTarget> findByDeploymentIdIn(Collection<UUID> deploymentIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ServiceTarget t where t.deploymentId = :deploymentId")
    List<ServiceTarget> findByDeploymentIdForUpdate(@Param("deploymentId") UUID deploymentId);
    Optional<ServiceTarget> findByServiceIdAndDeploymentId(UUID serviceId, UUID deploymentId);
}
