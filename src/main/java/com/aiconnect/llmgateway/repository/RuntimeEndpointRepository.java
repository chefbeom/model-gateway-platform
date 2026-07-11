package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RuntimeEndpointRepository extends JpaRepository<RuntimeEndpoint, UUID> {
    List<RuntimeEndpoint> findByNodeId(UUID nodeId);
    List<RuntimeEndpoint> findByEnabledTrue();
    boolean existsByBaseUrl(String baseUrl);
}
