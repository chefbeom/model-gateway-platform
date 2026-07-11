package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_request_attempt")
public class LlmRequestAttempt {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID requestId;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID deploymentId;
    @Column(nullable = false) private int attemptNumber;
    @Column(nullable = false, length = 32) private String status = "IN_PROGRESS";
    @Column(nullable = false) private Instant startedAt = Instant.now();
    private Instant completedAt;
    private Long latencyMs;
    private Integer httpStatus;
    @Column(length = 80) private String errorType;
    @Column(length = 1000) private String errorMessage;
    @Column(nullable = false) private boolean responseStarted;

    protected LlmRequestAttempt() { }
    public LlmRequestAttempt(UUID requestId, UUID deploymentId, int attemptNumber) { this.requestId = requestId; this.deploymentId = deploymentId; this.attemptNumber = attemptNumber; }
    public void markResponseStarted() { this.responseStarted = true; }
    public void succeed(long latencyMs, int httpStatus) {
        this.responseStarted = true; this.status = "SUCCEEDED"; this.latencyMs = latencyMs;
        this.httpStatus = httpStatus; this.completedAt = Instant.now();
    }
    public void fail(String errorType, String errorMessage, long latencyMs, Integer httpStatus) {
        if ("STREAM_INTERRUPTED".equals(errorType)) this.responseStarted = true;
        this.status = "FAILED"; this.errorType = errorType; this.errorMessage = truncate(errorMessage);
        this.latencyMs = latencyMs; this.httpStatus = httpStatus; this.completedAt = Instant.now();
    }
    private String truncate(String value) { return value == null ? null : value.substring(0, Math.min(value.length(), 1000)); }
    public UUID getId() { return id; }
    public boolean isResponseStarted() { return responseStarted; }
}
