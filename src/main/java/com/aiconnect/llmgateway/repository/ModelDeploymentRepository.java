package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.ModelDeployment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModelDeploymentRepository extends JpaRepository<ModelDeployment, UUID> {
    List<ModelDeployment> findByRuntimeEndpointId(UUID runtimeEndpointId);
    List<ModelDeployment> findByExternalProviderId(UUID externalProviderId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ModelDeployment m where m.id = :id")
    Optional<ModelDeployment> findByIdForUpdate(@Param("id") UUID id);
}
