package com.aiconnect.llmgateway.retention;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_request_content")
public class RequestContent {
    @Id @Column(name = "request_id", columnDefinition = "char(36)") private UUID requestId;
    @Column(nullable = false, columnDefinition = "text") private String encryptedRequest;
    @Column(columnDefinition = "text") private String encryptedResponse;
    @Column(nullable = false) private Instant capturedAt = Instant.now();
    protected RequestContent() { }
    public RequestContent(UUID requestId, String encryptedRequest, String encryptedResponse) { this.requestId = requestId; this.encryptedRequest = encryptedRequest; this.encryptedResponse = encryptedResponse; }
    public UUID getRequestId() { return requestId; }
    public String getEncryptedRequest() { return encryptedRequest; }
    public String getEncryptedResponse() { return encryptedResponse; }
}
