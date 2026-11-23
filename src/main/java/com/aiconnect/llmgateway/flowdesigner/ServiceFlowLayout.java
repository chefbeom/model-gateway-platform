package com.aiconnect.llmgateway.flowdesigner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Per-service canvas layout (node ids, coordinates, collapsed flags, viewport only). */
@Entity
@Table(name = "service_flow_layout")
public class ServiceFlowLayout {
    @Id @Column(columnDefinition = "char(36)") private UUID serviceId;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID organizationId;
    @Column(nullable = false, columnDefinition = "text") private String layoutJson;
    @Column(nullable = false) private long revision;
    @Column(columnDefinition = "char(36)") private UUID updatedBy;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected ServiceFlowLayout() { }

    public ServiceFlowLayout(UUID serviceId, UUID organizationId, String layoutJson, UUID actor) {
        this.serviceId = serviceId;
        this.organizationId = organizationId;
        this.layoutJson = layoutJson;
        this.revision = 1;
        this.updatedBy = actor;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void replace(String json, UUID actor) {
        this.layoutJson = json;
        this.updatedBy = actor;
        this.revision++;
        this.updatedAt = Instant.now();
    }

    public UUID getServiceId() { return serviceId; }
    public UUID getOrganizationId() { return organizationId; }
    public String getLayoutJson() { return layoutJson; }
    public long getRevision() { return revision; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
