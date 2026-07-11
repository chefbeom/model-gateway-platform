package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accelerator_device")
public class AcceleratorDevice {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID nodeId;
    @Column(length = 80) private String vendor;
    @Column(length = 240) private String productName;
    @Column(nullable = false) private int deviceIndex;
    @Column(length = 160) private String deviceUuid;
    private Integer memoryTotalMb;
    @Column(length = 120) private String driverVersion;
    @Column(columnDefinition = "text") private String metadataJson;
    private Instant lastSeenAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected AcceleratorDevice() { }
    public AcceleratorDevice(UUID nodeId, String vendor, String productName, int deviceIndex, String deviceUuid, Integer memoryTotalMb, String driverVersion, String metadataJson) {
        this.nodeId = nodeId; this.vendor = vendor; this.productName = productName; this.deviceIndex = deviceIndex; this.deviceUuid = deviceUuid;
        this.memoryTotalMb = memoryTotalMb; this.driverVersion = driverVersion; this.metadataJson = metadataJson; this.lastSeenAt = Instant.now();
    }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getNodeId() { return nodeId; }
    public String getVendor() { return vendor; }
    public String getProductName() { return productName; }
    public int getDeviceIndex() { return deviceIndex; }
    public String getDeviceUuid() { return deviceUuid; }
    public Integer getMemoryTotalMb() { return memoryTotalMb; }
    public String getDriverVersion() { return driverVersion; }
    public String getMetadataJson() { return metadataJson; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
