package com.aiconnect.llmgateway.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_alert_policy")
public class ProjectAlertPolicy {
    @Id
    @Column(name = "project_id", columnDefinition = "char(36)")
    private UUID projectId;

    private Integer requestsPerMinuteThreshold;
    @Column(precision = 5, scale = 2)
    private BigDecimal errorRatePercentThreshold;
    private Integer monthlyTokenUsagePercentThreshold;
    @Column(nullable = false)
    private int cooldownSeconds = 900;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProjectAlertPolicy() { }

    public ProjectAlertPolicy(UUID projectId, Integer requestsPerMinuteThreshold, BigDecimal errorRatePercentThreshold,
                              Integer monthlyTokenUsagePercentThreshold, Integer cooldownSeconds) {
        this.projectId = projectId;
        this.requestsPerMinuteThreshold = requestsPerMinuteThreshold;
        this.errorRatePercentThreshold = errorRatePercentThreshold;
        this.monthlyTokenUsagePercentThreshold = monthlyTokenUsagePercentThreshold;
        this.cooldownSeconds = cooldownSeconds == null ? 900 : Math.max(60, cooldownSeconds);
    }

    @PreUpdate
    void updateTimestamp() { updatedAt = Instant.now(); }

    public UUID getProjectId() { return projectId; }
    public Integer getRequestsPerMinuteThreshold() { return requestsPerMinuteThreshold; }
    public BigDecimal getErrorRatePercentThreshold() { return errorRatePercentThreshold; }
    public Integer getMonthlyTokenUsagePercentThreshold() { return monthlyTokenUsagePercentThreshold; }
    public int getCooldownSeconds() { return cooldownSeconds; }
}
