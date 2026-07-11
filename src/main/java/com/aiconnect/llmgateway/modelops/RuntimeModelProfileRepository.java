package com.aiconnect.llmgateway.modelops;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface RuntimeModelProfileRepository extends JpaRepository<RuntimeModelProfile, UUID> { List<RuntimeModelProfile> findByRuntimeEndpointIdOrderByNameAsc(UUID runtimeEndpointId); }
