package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.ServiceTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ServiceTargetRepository extends JpaRepository<ServiceTarget, UUID> {
    List<ServiceTarget> findByServiceIdAndEnabledTrueOrderByPriorityAsc(UUID serviceId);
    List<ServiceTarget> findByServiceIdOrderByPriorityAsc(UUID serviceId);
    List<ServiceTarget> findByDeploymentIdIn(Collection<UUID> deploymentIds);
}
