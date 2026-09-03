package com.aiconnect.llmgateway.diagnostic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable, non-sensitive failure diagnosis attached to an internal request row. */
@Entity
@Table(name = "llm_request_diagnostic")
public class RequestDiagnostic {
    @Id
    @Column(name = "request_id", columnDefinition = "char(36)")
    private UUID requestId;

    @Column(nullable = false)
    private int schemaVersion = 1;

    @Column(nullable = false, columnDefinition = "mediumtext")
    private String payloadJson;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected RequestDiagnostic() { }

    public RequestDiagnostic(UUID requestId, String payloadJson) {
        this.requestId = requestId;
        this.payloadJson = payloadJson;
    }

    public UUID getRequestId() { return requestId; }
    public int getSchemaVersion() { return schemaVersion; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
}
