package com.aiconnect.llmgateway.retention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_content_policy")
public class ProjectContentPolicy {
    @Id
    @Column(name = "project_id", columnDefinition = "char(36)")
    private UUID projectId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ContentRetentionMode retentionMode = ContentRetentionMode.METADATA_ONLY;
    private Integer retentionDays;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProjectContentPolicy() { }

    public ProjectContentPolicy(UUID projectId, ContentRetentionMode retentionMode) {
        this(projectId, retentionMode, null);
    }

    public ProjectContentPolicy(UUID projectId, ContentRetentionMode retentionMode, Integer retentionDays) {
        this.projectId = projectId;
        this.retentionMode = retentionMode;
        this.retentionDays = retentionMode == ContentRetentionMode.FULL_ENCRYPTED
                ? Math.min(365, Math.max(1, retentionDays == null ? 30 : retentionDays))
                : null;
    }

    @PreUpdate
    void updateTimestamp() { updatedAt = Instant.now(); }

    public UUID getProjectId() { return projectId; }
    public ContentRetentionMode getRetentionMode() { return retentionMode; }
    public Integer getRetentionDays() { return retentionDays; }
}
