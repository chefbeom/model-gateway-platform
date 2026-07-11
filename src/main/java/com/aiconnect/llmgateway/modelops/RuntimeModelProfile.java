package com.aiconnect.llmgateway.modelops;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "runtime_model_profile")
public class RuntimeModelProfile {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID runtimeEndpointId;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 500) private String modelKey;
    @Column(nullable = false, columnDefinition = "text") private String configJson;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected RuntimeModelProfile() { }
    public RuntimeModelProfile(UUID runtimeEndpointId, String name, String modelKey, String configJson) { this.runtimeEndpointId = runtimeEndpointId; this.name = name; this.modelKey = modelKey; this.configJson = configJson; }
    @PreUpdate void touch() { updatedAt = Instant.now(); }
    public UUID getId() { return id; } public UUID getRuntimeEndpointId() { return runtimeEndpointId; } public String getName() { return name; } public String getModelKey() { return modelKey; } public String getConfigJson() { return configJson; }
}
