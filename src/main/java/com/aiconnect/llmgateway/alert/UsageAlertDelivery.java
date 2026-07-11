package com.aiconnect.llmgateway.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_alert_delivery")
public class UsageAlertDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID projectId;
    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID notificationChannelId;
    @Column(nullable = false, length = 48)
    private String metric;
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal observedValue;
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal thresholdValue;
    @Column(nullable = false, length = 24)
    private String status = "PENDING";
    @Column(length = 1000)
    private String errorMessage;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected UsageAlertDelivery() { }
    public UsageAlertDelivery(UUID projectId, UUID channelId, UsageAlertMetric metric, BigDecimal observed, BigDecimal threshold) {
        this.projectId = projectId;
        this.notificationChannelId = channelId;
        this.metric = metric.name();
        this.observedValue = observed;
        this.thresholdValue = threshold;
    }
    public void succeed() { status = "SENT"; }
    public void fail(String message) { status = "FAILED"; errorMessage = message == null ? "Delivery failed" : message.substring(0, Math.min(1000, message.length())); }
}
