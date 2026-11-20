package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.domain.RuntimeType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connection_registration_request")
public class ConnectionRegistrationRequest {
    public enum Kind { RUNTIME, EXTERNAL_PROVIDER }
    public enum Status { PENDING, APPROVED, REJECTED }
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID projectId;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID requesterId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private Kind kind;
    @Column(nullable = false, length = 160) private String displayName;
    @Column(nullable = false, length = 500) private String baseUrl;
    @Enumerated(EnumType.STRING) @Column(length = 40) private RuntimeType runtimeType;
    @Column(columnDefinition = "text") private String encryptedSecret;
    @Column(nullable = false, length = 1000) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private Status status = Status.PENDING;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    private Instant decidedAt;
    @Column(columnDefinition = "char(36)") private UUID decidedBy;
    @Column(length = 1000) private String decisionReason;
    @Column(columnDefinition = "char(36)") private UUID resultId;
    protected ConnectionRegistrationRequest() { }
    public ConnectionRegistrationRequest(UUID projectId, UUID requesterId, Kind kind, String displayName, String baseUrl,
            RuntimeType runtimeType, String encryptedSecret, String reason) {
        this.projectId=projectId; this.requesterId=requesterId; this.kind=kind; this.displayName=displayName;
        this.baseUrl=baseUrl; this.runtimeType=runtimeType; this.encryptedSecret=encryptedSecret; this.reason=reason;
    }
    public void decide(Status status, UUID actorId, String decisionReason, UUID resultId) {
        if (this.status != Status.PENDING || status == Status.PENDING) throw new IllegalStateException("Invalid decision");
        this.status=status; this.decidedBy=actorId; this.decidedAt=Instant.now(); this.decisionReason=decisionReason;
        this.resultId=resultId; this.encryptedSecret=null;
    }
    public UUID getId(){return id;} public UUID getProjectId(){return projectId;} public UUID getRequesterId(){return requesterId;}
    public Kind getKind(){return kind;} public String getDisplayName(){return displayName;} public String getBaseUrl(){return baseUrl;}
    public RuntimeType getRuntimeType(){return runtimeType;} public String getEncryptedSecret(){return encryptedSecret;}
    public String getReason(){return reason;} public Status getStatus(){return status;} public Instant getCreatedAt(){return createdAt;}
    public Instant getDecidedAt(){return decidedAt;} public UUID getDecidedBy(){return decidedBy;}
    public String getDecisionReason(){return decisionReason;} public UUID getResultId(){return resultId;}
}
