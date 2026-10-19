package com.aiconnect.llmgateway.scheduling;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "request_scheduling_trace")
public class RequestSchedulingTrace {
    @Id @Column(length=120) private String requestId;
    @Column(columnDefinition="char(36)", nullable=false) private UUID serviceId;
    @Column(nullable=false, length=32) private String mode;
    @Column(nullable=false, length=24) private String phase;
    private Instant queuedAt;
    private Instant dispatchedAt;
    private Instant completedAt;
    private Instant deadline;
    @Column(nullable=false) private long queueWaitMs;
    private Long executionMs;
    @Column(length=64) private String dispatchReason;
    @Column(length=80) private String errorCode;
    @Column(columnDefinition="char(36)") private UUID deploymentId;
    protected RequestSchedulingTrace() { }
    public RequestSchedulingTrace(String requestId, UUID serviceId, SchedulingMode mode, long now, long deadline) {
        this.requestId=requestId; this.serviceId=serviceId; this.mode=mode.name(); this.phase="QUEUED";
        this.queuedAt=Instant.ofEpochMilli(now); this.deadline=Instant.ofEpochMilli(deadline);
    }
    public void dispatch(UUID deploymentId, String reason, long now) {
        phase="RUNNING"; this.deploymentId=deploymentId; dispatchReason=reason;
        dispatchedAt=Instant.ofEpochMilli(now); queueWaitMs=Math.max(0,now-queuedAt.toEpochMilli());
    }
    public void finish(String phase, String code, long now) {
        this.phase=phase; errorCode=code; completedAt=Instant.ofEpochMilli(now);
        if(dispatchedAt!=null) executionMs=Math.max(0, now-dispatchedAt.toEpochMilli());
        else queueWaitMs=Math.max(0,now-queuedAt.toEpochMilli());
    }
    public View view() { return new View(mode,phase,queuedAt,dispatchedAt,completedAt,deadline,"QUEUED".equals(phase)?Math.max(0,System.currentTimeMillis()-queuedAt.toEpochMilli()):queueWaitMs,executionMs,dispatchReason,errorCode,deploymentId); }
    public String getRequestId() { return requestId; }
    public record View(String mode,String phase,Instant queuedAt,Instant dispatchedAt,Instant completedAt,Instant deadline,
                       long queueWaitMs,Long executionMs,String dispatchReason,String errorCode,UUID deploymentId) { }
}
