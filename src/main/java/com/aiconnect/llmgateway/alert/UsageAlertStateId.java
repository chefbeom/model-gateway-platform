package com.aiconnect.llmgateway.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class UsageAlertStateId implements Serializable {
    @Column(name = "project_id", columnDefinition = "char(36)")
    private UUID projectId;
    @Column(length = 48)
    private String metric;

    protected UsageAlertStateId() { }
    public UsageAlertStateId(UUID projectId, UsageAlertMetric metric) {
        this.projectId = projectId;
        this.metric = metric.name();
    }
    @Override public boolean equals(Object other) { return other instanceof UsageAlertStateId id && Objects.equals(projectId, id.projectId) && Objects.equals(metric, id.metric); }
    @Override public int hashCode() { return Objects.hash(projectId, metric); }
}
