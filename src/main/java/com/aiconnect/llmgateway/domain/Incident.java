package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incident")
public class Incident {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID runtimeEndpointId;
    @Column(nullable = false, length = 24) private String status = "OPEN";
    @Column(nullable = false, length = 500) private String reason;
    @Column(nullable = false) private Instant openedAt = Instant.now();
    private Instant recoveredAt;
    protected Incident() { }
    public Incident(UUID runtimeEndpointId, String reason) { this.runtimeEndpointId = runtimeEndpointId; this.reason = reason; }
    public void recover() { status = "RECOVERED"; recoveredAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getRuntimeEndpointId() { return runtimeEndpointId; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getRecoveredAt() { return recoveredAt; }
}
