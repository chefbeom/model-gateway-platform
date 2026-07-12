package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.ModelDeployment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ModelDeploymentRepository extends JpaRepository<ModelDeployment, UUID> {
    List<ModelDeployment> findByRuntimeEndpointId(UUID runtimeEndpointId);
    List<ModelDeployment> findByExternalProviderId(UUID externalProviderId);
}
