package com.aiconnect.llmgateway.node;

import com.aiconnect.llmgateway.domain.AcceleratorDevice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/nodes/{nodeId}/accelerators")
public class AcceleratorDeviceController {
    private final AcceleratorDeviceService devices;
    public AcceleratorDeviceController(AcceleratorDeviceService devices) { this.devices = devices; }
    @PostMapping public DeviceView register(@PathVariable UUID nodeId, @Valid @RequestBody RegisterDevice request) { return DeviceView.from(devices.register(nodeId, request.vendor(), request.productName(), request.deviceIndex(), request.deviceUuid(), request.memoryTotalMb(), request.driverVersion(), request.metadataJson())); }
    @GetMapping public List<DeviceView> list(@PathVariable UUID nodeId) { return devices.list(nodeId).stream().map(DeviceView::from).toList(); }
    public record RegisterDevice(String vendor, String productName, @Min(0) int deviceIndex, String deviceUuid, @Min(1) Integer memoryTotalMb, String driverVersion, String metadataJson) { }
    @PatchMapping("/{deviceId}") public DeviceView update(@PathVariable UUID nodeId, @PathVariable UUID deviceId, @Valid @RequestBody RegisterDevice request) { return DeviceView.from(devices.update(nodeId, deviceId, request.vendor(), request.productName(), request.deviceIndex(), request.deviceUuid(), request.memoryTotalMb(), request.driverVersion(), request.metadataJson())); }
    @DeleteMapping("/{deviceId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID nodeId, @PathVariable UUID deviceId) { devices.delete(nodeId, deviceId); }
    public record DeviceView(UUID id, UUID nodeId, String vendor, String productName, int deviceIndex, String deviceUuid, Integer memoryTotalMb, String driverVersion, String metadataJson, Instant lastSeenAt) {
        static DeviceView from(AcceleratorDevice device) { return new DeviceView(device.getId(), device.getNodeId(), device.getVendor(), device.getProductName(), device.getDeviceIndex(), device.getDeviceUuid(), device.getMemoryTotalMb(), device.getDriverVersion(), device.getMetadataJson(), device.getLastSeenAt()); }
    }
}
