package com.aiconnect.llmgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Encrypted transcript for an administrator's direct model-test conversation. */
@Entity
@Table(name = "playground_conversation")
public class PlaygroundConversation {
    @Id
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID organizationId;

    @Column(columnDefinition = "char(36)")
    private UUID actorUserId;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID targetId;

    @Column(nullable = false, length = 160)
    private String targetName;

    @Column(nullable = false, length = 500)
    private String modelId;

    @Column(nullable = false, columnDefinition = "mediumtext")
    private String encryptedTranscript;

    @Column(nullable = false)
    private int messageCount;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected PlaygroundConversation() { }

    public PlaygroundConversation(UUID organizationId, UUID actorUserId, UUID targetId,
                                  String targetName, String modelId, String encryptedTranscript,
                                  int messageCount) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.actorUserId = actorUserId;
        this.targetId = targetId;
        this.targetName = targetName;
        this.modelId = modelId;
        this.encryptedTranscript = encryptedTranscript;
        this.messageCount = messageCount;
    }

    public void update(String encryptedTranscript, int messageCount) {
        this.encryptedTranscript = encryptedTranscript;
        this.messageCount = messageCount;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void setTimestamps() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getActorUserId() { return actorUserId; }
    public UUID getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
    public String getModelId() { return modelId; }
    public String getEncryptedTranscript() { return encryptedTranscript; }
    public int getMessageCount() { return messageCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
