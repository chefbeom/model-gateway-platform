package com.aiconnect.llmgateway.modelops;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "runtime_model_operation")
public class RuntimeModelOperation {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID runtimeEndpointId;
    @Column(columnDefinition = "char(36)") private UUID profileId;
    @Column(nullable = false, length = 500) private String modelKey;
    @Column(nullable = false, length = 32) private String operationType;
    @Column(nullable = false, length = 32) private String status = "RUNNING";
    @Column(columnDefinition = "text") private String requestJson;
    @Column(columnDefinition = "text") private String resultJson;
    @Column(length = 1000) private String message;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    private Instant completedAt;
    protected RuntimeModelOperation() { }
    public RuntimeModelOperation(UUID endpointId, UUID profileId, String modelKey, String type, String requestJson) { this.runtimeEndpointId = endpointId; this.profileId = profileId; this.modelKey = modelKey; this.operationType = type; this.requestJson = requestJson; }
    public void complete(String resultJson, String message) { status = "SUCCEEDED"; this.resultJson = resultJson; this.message = message; completedAt = Instant.now(); }
    public void waitForDrain(String message) { status = "WAITING_FOR_DRAIN"; this.message = message; }
    public void fail(String message) { status = "FAILED"; this.message = message == null ? "Operation failed" : message.substring(0, Math.min(1000, message.length())); completedAt = Instant.now(); }
    public UUID getId() { return id; } public UUID getRuntimeEndpointId() { return runtimeEndpointId; } public UUID getProfileId() { return profileId; } public String getModelKey() { return modelKey; } public String getOperationType() { return operationType; } public String getStatus() { return status; } public String getRequestJson() { return requestJson; } public String getResultJson() { return resultJson; } public String getMessage() { return message; } public Instant getCreatedAt() { return createdAt; } public Instant getCompletedAt() { return completedAt; }
}
