package com.aiconnect.llmgateway.notification;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_channel")
public class NotificationChannel {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID organizationId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private NotificationChannelType channelType;
    @Column(nullable = false, columnDefinition = "text") private String encryptedTarget;
    @Column(columnDefinition = "text") private String encryptedSecret;
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected NotificationChannel() { }

    public NotificationChannel(UUID organizationId, NotificationChannelType channelType, String encryptedTarget, String encryptedSecret) {
        this.organizationId = organizationId;
        this.channelType = channelType;
        this.encryptedTarget = encryptedTarget;
        this.encryptedSecret = encryptedSecret;
    }

    @PreUpdate
    void updateTimestamp() { updatedAt = Instant.now(); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public NotificationChannelType getChannelType() { return channelType; }
    public String getEncryptedTarget() { return encryptedTarget; }
    public String getEncryptedSecret() { return encryptedSecret; }
    public boolean isEnabled() { return enabled; }
}
