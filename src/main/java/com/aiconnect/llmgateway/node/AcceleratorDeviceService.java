package com.aiconnect.llmgateway.node;

import com.aiconnect.llmgateway.domain.AcceleratorDevice;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.AcceleratorDeviceRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AcceleratorDeviceService {
    private final InferenceNodeRepository nodes;
    private final AcceleratorDeviceRepository devices;
    private final AuditService audit;
    public AcceleratorDeviceService(InferenceNodeRepository nodes, AcceleratorDeviceRepository devices, AuditService audit) { this.nodes = nodes; this.devices = devices; this.audit = audit; }
    @Transactional
    public AcceleratorDevice register(UUID nodeId, String vendor, String productName, int deviceIndex, String deviceUuid, Integer memoryTotalMb, String driverVersion, String metadataJson) {
        var node = nodes.findById(nodeId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND", "The inference node does not exist."));
        AcceleratorDevice device = devices.save(new AcceleratorDevice(nodeId, vendor, productName, deviceIndex, deviceUuid, memoryTotalMb, driverVersion, metadataJson));
        audit.record(node.getOrganizationId(), CurrentActor.userIdOrNull(), "ACCELERATOR_REGISTERED", "ACCELERATOR_DEVICE", device.getId(), Map.of("deviceIndex", deviceIndex));
        return device;
    }
    @Transactional
    public AcceleratorDevice update(UUID nodeId, UUID deviceId, String vendor, String productName, int deviceIndex,
                                    String deviceUuid, Integer memoryTotalMb, String driverVersion, String metadataJson) {
        InferenceNode node = nodes.findById(nodeId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND", "The inference node does not exist."));
        AcceleratorDevice device = devices.findById(deviceId).filter(item -> item.getNodeId().equals(nodeId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCELERATOR_NOT_FOUND", "The accelerator device does not exist on this node."));
        device.configure(vendor, productName, deviceIndex, deviceUuid, memoryTotalMb, driverVersion, metadataJson);
        AcceleratorDevice saved = devices.save(device);
        audit.record(node.getOrganizationId(), CurrentActor.userIdOrNull(), "ACCELERATOR_UPDATED", "ACCELERATOR_DEVICE", saved.getId(), Map.of("deviceIndex", deviceIndex));
        return saved;
    }

    @Transactional
    public void delete(UUID nodeId, UUID deviceId) {
        InferenceNode node = nodes.findById(nodeId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND", "The inference node does not exist."));
        AcceleratorDevice device = devices.findById(deviceId).filter(item -> item.getNodeId().equals(nodeId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCELERATOR_NOT_FOUND", "The accelerator device does not exist on this node."));
        devices.delete(device);
        audit.record(node.getOrganizationId(), CurrentActor.userIdOrNull(), "ACCELERATOR_DELETED", "ACCELERATOR_DEVICE", deviceId, Map.of("deviceIndex", device.getDeviceIndex()));
    }
    public List<AcceleratorDevice> list(UUID nodeId) { return devices.findByNodeIdOrderByDeviceIndexAsc(nodeId); }
}
