package com.aiconnect.llmgateway.notification;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery")
public class NotificationDelivery {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID incidentId;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID notificationChannelId;
    @Column(nullable = false, length = 32) private String eventType;
    @Column(nullable = false, length = 24) private String status = "PENDING";
    @Column(length = 1000) private String errorMessage;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    protected NotificationDelivery() { }
    public NotificationDelivery(UUID incidentId, UUID notificationChannelId, String eventType) { this.incidentId = incidentId; this.notificationChannelId = notificationChannelId; this.eventType = eventType; }
    public void succeed() { status = "SENT"; }
    public void fail(String message) { status = "FAILED"; errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 1000)); }
    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public UUID getNotificationChannelId() { return notificationChannelId; }
    public String getEventType() { return eventType; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}
