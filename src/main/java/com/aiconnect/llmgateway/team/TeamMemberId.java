package com.aiconnect.llmgateway.team;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class TeamMemberId implements Serializable {
    @Column(name = "team_id", columnDefinition = "char(36)")
    private UUID teamId;

    @Column(name = "user_id", columnDefinition = "char(36)")
    private UUID userId;

    protected TeamMemberId() { }

    public TeamMemberId(UUID teamId, UUID userId) {
        this.teamId = teamId;
        this.userId = userId;
    }

    public UUID getTeamId() { return teamId; }
    public UUID getUserId() { return userId; }

    @Override
    public boolean equals(Object other) {
        return other instanceof TeamMemberId id
                && Objects.equals(teamId, id.teamId)
                && Objects.equals(userId, id.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamId, userId);
    }
}
