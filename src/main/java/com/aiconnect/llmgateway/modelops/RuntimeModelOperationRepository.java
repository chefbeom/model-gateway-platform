package com.aiconnect.llmgateway.modelops;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface RuntimeModelOperationRepository extends JpaRepository<RuntimeModelOperation, UUID> { List<RuntimeModelOperation> findTop30ByRuntimeEndpointIdOrderByCreatedAtDesc(UUID runtimeEndpointId); }
