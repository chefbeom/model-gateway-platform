package com.aiconnect.llmgateway.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(columnDefinition = "char(36)") private UUID organizationId;
    @Column(columnDefinition = "char(36)") private UUID actorUserId;
    @Column(nullable = false, length = 120) private String action;
    @Column(nullable = false, length = 80) private String resourceType;
    @Column(length = 80) private String resourceId;
    @Column(columnDefinition = "text") private String detailJson;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    protected AuditLog() { }

    public AuditLog(UUID organizationId, UUID actorUserId, String action, String resourceType, String resourceId, String detailJson) {
        this.organizationId = organizationId;
        this.actorUserId = actorUserId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.detailJson = detailJson;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getActorUserId() { return actorUserId; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getDetailJson() { return detailJson; }
    public Instant getCreatedAt() { return createdAt; }
}
