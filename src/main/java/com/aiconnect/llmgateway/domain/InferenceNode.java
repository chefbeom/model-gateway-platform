package com.aiconnect.llmgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inference_node")
public class InferenceNode {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID organizationId;
    @Column(nullable = false, length = 120) private String name;
    @Column(length = 500) private String description;
    @Column(nullable = false, length = 24) private String connectionMode = "DIRECT";
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private HealthStatus status = HealthStatus.UNKNOWN;
    @Column(columnDefinition = "text") private String labelsJson;
    private Instant lastHeartbeatAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected InferenceNode() { }

    public InferenceNode(UUID organizationId, String name, String description, String connectionMode, String labelsJson) {
        this.organizationId = organizationId;
        this.name = name;
        this.description = description;
        this.connectionMode = connectionMode;
        this.labelsJson = labelsJson;
    }

    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getConnectionMode() { return connectionMode; }
    public HealthStatus getStatus() { return status; }
}
