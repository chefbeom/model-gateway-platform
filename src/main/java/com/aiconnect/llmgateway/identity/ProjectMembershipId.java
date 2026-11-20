package com.aiconnect.llmgateway.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ProjectMembershipId implements Serializable {
    @Column(name = "project_id", columnDefinition = "char(36)") private UUID projectId;
    @Column(name = "user_id", columnDefinition = "char(36)") private UUID userId;
    protected ProjectMembershipId() { }
    public ProjectMembershipId(UUID projectId, UUID userId) { this.projectId = projectId; this.userId = userId; }
    public UUID getProjectId() { return projectId; }
    public UUID getUserId() { return userId; }
    @Override public boolean equals(Object o) { return o instanceof ProjectMembershipId id && Objects.equals(projectId, id.projectId) && Objects.equals(userId, id.userId); }
    @Override public int hashCode() { return Objects.hash(projectId, userId); }
}
