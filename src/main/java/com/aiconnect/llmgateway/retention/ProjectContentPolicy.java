package com.aiconnect.llmgateway.retention;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_content_policy")
public class ProjectContentPolicy {
    @Id @Column(name = "project_id", columnDefinition = "char(36)") private UUID projectId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ContentRetentionMode retentionMode = ContentRetentionMode.METADATA_ONLY;
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected ProjectContentPolicy() { }
    public ProjectContentPolicy(UUID projectId, ContentRetentionMode retentionMode) { this.projectId = projectId; this.retentionMode = retentionMode; }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getProjectId() { return projectId; }
    public ContentRetentionMode getRetentionMode() { return retentionMode; }
}
