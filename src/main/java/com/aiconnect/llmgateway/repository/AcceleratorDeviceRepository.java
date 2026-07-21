package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.AcceleratorDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AcceleratorDeviceRepository extends JpaRepository<AcceleratorDevice, UUID> {
    List<AcceleratorDevice> findByNodeIdOrderByDeviceIndexAsc(UUID nodeId);
    void deleteByNodeId(UUID nodeId);
}
