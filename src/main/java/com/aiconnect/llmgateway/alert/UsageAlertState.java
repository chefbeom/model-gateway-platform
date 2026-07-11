package com.aiconnect.llmgateway.alert;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_alert_state")
public class UsageAlertState {
    @EmbeddedId
    private UsageAlertStateId id;
    private Instant lastSentAt;

    protected UsageAlertState() { }
    public UsageAlertState(UUID projectId, UsageAlertMetric metric) { this.id = new UsageAlertStateId(projectId, metric); }
    public Instant getLastSentAt() { return lastSentAt; }
    public void markSent(Instant sentAt) { lastSentAt = sentAt; }
}
