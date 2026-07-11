package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service_target")
public class ServiceTarget {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID serviceId;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID deploymentId;
    @Column(nullable = false) private int priority;
    @Column(nullable = false) private int weight = 100;
    @Column(nullable = false) private boolean degraded;
    @Column(nullable = false) private boolean enabled = true;
    private Integer maxConcurrencyOverride;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected ServiceTarget() { }
    public ServiceTarget(UUID serviceId, UUID deploymentId, int priority, int weight, boolean degraded, Integer maxConcurrencyOverride) {
        this.serviceId = serviceId; this.deploymentId = deploymentId; this.priority = priority; this.weight = weight;
        this.degraded = degraded; this.maxConcurrencyOverride = maxConcurrencyOverride;
    }
    public void configure(Integer priority, Integer weight, Boolean degraded, Boolean enabled, Integer maxConcurrencyOverride) {
        if (priority != null) this.priority = Math.max(1, priority);
        if (weight != null) this.weight = Math.max(1, weight);
        if (degraded != null) this.degraded = degraded;
        if (enabled != null) this.enabled = enabled;
        if (maxConcurrencyOverride != null) this.maxConcurrencyOverride = Math.max(1, maxConcurrencyOverride);
    }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getServiceId() { return serviceId; }
    public UUID getDeploymentId() { return deploymentId; }
    public int getPriority() { return priority; }
    public int getWeight() { return weight; }
    public boolean isDegraded() { return degraded; }
    public boolean isEnabled() { return enabled; }
    public Integer getMaxConcurrencyOverride() { return maxConcurrencyOverride; }
    public int effectiveMaxConcurrency(int deploymentLimit) { return maxConcurrencyOverride == null ? deploymentLimit : maxConcurrencyOverride; }
}
