package com.aiconnect.llmgateway.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_membership")
public class ProjectMembership {
    public enum Role { MANAGER, MEMBER }
    @EmbeddedId private ProjectMembershipId id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private Role role;
    @Column(name = "can_issue_api_keys", nullable = false) private boolean canIssueApiKeys = false;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    protected ProjectMembership() { }

    public ProjectMembership(UUID projectId, UUID userId, Role role) {
        this(projectId, userId, role, role == Role.MANAGER);
    }

    public ProjectMembership(UUID projectId, UUID userId, Role role, boolean canIssueApiKeys) {
        this.id = new ProjectMembershipId(projectId, userId);
        this.role = role;
        this.canIssueApiKeys = role == Role.MANAGER || canIssueApiKeys;
    }

    public ProjectMembershipId getId() { return id; }
    public Role getRole() { return role; }
    public boolean isCanIssueApiKeys() { return role == Role.MANAGER || canIssueApiKeys; }
    public void setCanIssueApiKeys(boolean canIssueApiKeys) {
        this.canIssueApiKeys = role == Role.MANAGER || canIssueApiKeys;
    }
    public Instant getCreatedAt() { return createdAt; }
}
