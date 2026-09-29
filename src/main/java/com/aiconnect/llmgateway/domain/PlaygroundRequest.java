package com.aiconnect.llmgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Metadata-only trace for administrator-run direct model tests. Prompt/file content is never stored. */
@Entity
@Table(name = "playground_request", indexes = {
        @Index(name = "idx_playground_request_org_started", columnList = "organization_id, started_at"),
        @Index(name = "idx_playground_request_deployment", columnList = "deployment_id")
})
public class PlaygroundRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(nullable = false, length = 64, unique = true)
    private String requestId;
    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID organizationId;
    @Column(columnDefinition = "char(36)")
    private UUID deploymentId;
    @Column(nullable = false, length = 24)
    private String targetType;
    @Column(nullable = false, length = 160)
    private String targetName;
    @Column(nullable = false, length = 500)
    private String modelId;
    @Column(nullable = false, length = 500)
    private String endpointUrl;
    @Column(nullable = false, length = 24)
    private String source = "PLAYGROUND";
    @Column(nullable = false, length = 24)
    private String status = "IN_PROGRESS";
    @Column(nullable = false)
    private boolean stream;
    private Integer httpStatus;
    private Long latencyMs;
    private Integer inputTokens;
    private Integer outputTokens;
    @Column(length = 80)
    private String errorCode;
    @Column(columnDefinition = "char(36)")
    private UUID actorUserId;
    @Column(nullable = false)
    private Instant startedAt = Instant.now();
    private Instant completedAt;

    protected PlaygroundRequest() { }

    public PlaygroundRequest(String requestId, UUID organizationId, UUID deploymentId, String targetType,
                             String targetName, String modelId, String endpointUrl, boolean stream, UUID actorUserId) {
        this.requestId = requestId;
        this.organizationId = organizationId;
        this.deploymentId = deploymentId;
        this.targetType = targetType;
        this.targetName = targetName;
        this.modelId = modelId;
        this.endpointUrl = endpointUrl;
        this.stream = stream;
        this.actorUserId = actorUserId;
    }

    public void complete(boolean successful, int httpStatus, long latencyMs, Integer inputTokens,
                         Integer outputTokens, String errorCode) {
        this.status = successful ? "SUCCEEDED" : "FAILED";
        this.httpStatus = httpStatus;
        this.latencyMs = latencyMs;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode.substring(0, Math.min(80, errorCode.length()));
        this.completedAt = Instant.now();
    }

    public void interrupted(long latencyMs, String errorCode) {
        complete(false, 502, latencyMs, inputTokens, outputTokens, errorCode);
    }

    public UUID getId() { return id; }
    public String getRequestId() { return requestId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getDeploymentId() { return deploymentId; }
    public String getTargetType() { return targetType; }
    public String getTargetName() { return targetName; }
    public String getModelId() { return modelId; }
    public String getEndpointUrl() { return endpointUrl; }
    public String getSource() { return source; }
    public String getStatus() { return status; }
    public boolean isStream() { return stream; }
    public Integer getHttpStatus() { return httpStatus; }
    public Long getLatencyMs() { return latencyMs; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public String getErrorCode() { return errorCode; }
    public UUID getActorUserId() { return actorUserId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }

    @PrePersist
    void setStartIfMissing() { if (startedAt == null) startedAt = Instant.now(); }
}
