package com.aiconnect.llmgateway.scheduling;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface ServiceSchedulingRepository extends JpaRepository<ServiceSchedulingPolicy, UUID> { }
