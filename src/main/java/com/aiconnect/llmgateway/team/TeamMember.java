package com.aiconnect.llmgateway.team;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "team_member")
public class TeamMember {
    @EmbeddedId
    private TeamMemberId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TeamRole role;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected TeamMember() { }

    public TeamMember(UUID teamId, UUID userId, TeamRole role) {
        this.id = new TeamMemberId(teamId, userId);
        this.role = role;
    }

    public TeamMemberId getId() { return id; }
    public TeamRole getRole() { return role; }
}
