package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    Optional<Incident> findFirstByRuntimeEndpointIdAndStatusOrderByOpenedAtDesc(UUID runtimeEndpointId, String status);
    List<Incident> findByRuntimeEndpointIdInOrderByOpenedAtDesc(Collection<UUID> runtimeEndpointIds);
}
