package com.aiconnect.llmgateway.quota;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_quota")
public class ProjectQuota {
    @Id
    @Column(name = "project_id", columnDefinition = "char(36)")
    private UUID projectId;
    @Column(nullable = false) private int requestsPerMinute = 60;
    private Long monthlyTokenLimit;
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected ProjectQuota() { }
    public ProjectQuota(UUID projectId, int requestsPerMinute, Long monthlyTokenLimit) {
        this.projectId = projectId; this.requestsPerMinute = requestsPerMinute; this.monthlyTokenLimit = monthlyTokenLimit;
    }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getProjectId() { return projectId; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public Long getMonthlyTokenLimit() { return monthlyTokenLimit; }
}
