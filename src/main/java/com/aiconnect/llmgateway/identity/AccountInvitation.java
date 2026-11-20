package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.team.TeamRole;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_invitation")
public class AccountInvitation {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, unique = true, length = 64, columnDefinition = "char(64)") private String tokenHash;
    @Column(nullable = false, length = 16) private String codePrefix;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AccountTier targetTier;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID organizationId;
    @Column(columnDefinition = "char(36)") private UUID teamId;
    @Enumerated(EnumType.STRING) @Column(length = 32) private TeamRole teamRole;
    @Column(columnDefinition = "char(36)") private UUID projectId;
    @Column(name = "can_issue_api_keys", nullable = false) private boolean canIssueApiKeys = false;
    @Column(columnDefinition = "char(36)") private UUID betaServiceId;
    @Column(length = 320) private String expectedEmail;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID issuedByUserId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AccountTier issuedByTier;
    @Column(nullable = false) private Instant expiresAt;
    private Instant consumedAt;
    @Column(columnDefinition = "char(36)") private UUID consumedByUserId;
    private Instant revokedAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    protected AccountInvitation() { }

    public AccountInvitation(String tokenHash, String codePrefix, AccountTier targetTier, UUID organizationId,
                             UUID teamId, TeamRole teamRole, UUID projectId, UUID betaServiceId,
                             String expectedEmail, UUID issuedByUserId, AccountTier issuedByTier, Instant expiresAt) {
        this(tokenHash, codePrefix, targetTier, organizationId, teamId, teamRole, projectId, false,
                betaServiceId, expectedEmail, issuedByUserId, issuedByTier, expiresAt);
    }

    public AccountInvitation(String tokenHash, String codePrefix, AccountTier targetTier, UUID organizationId,
                             UUID teamId, TeamRole teamRole, UUID projectId, boolean canIssueApiKeys, UUID betaServiceId,
                             String expectedEmail, UUID issuedByUserId, AccountTier issuedByTier, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.codePrefix = codePrefix;
        this.targetTier = targetTier;
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.teamRole = teamRole;
        this.projectId = projectId;
        this.canIssueApiKeys = canIssueApiKeys;
        this.betaServiceId = betaServiceId;
        this.expectedEmail = expectedEmail;
        this.issuedByUserId = issuedByUserId;
        this.issuedByTier = issuedByTier;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getTokenHash() { return tokenHash; }
    public String getCodePrefix() { return codePrefix; }
    public AccountTier getTargetTier() { return targetTier; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getTeamId() { return teamId; }
    public TeamRole getTeamRole() { return teamRole; }
    public UUID getProjectId() { return projectId; }
    public boolean isCanIssueApiKeys() { return canIssueApiKeys; }
    public UUID getBetaServiceId() { return betaServiceId; }
    public String getExpectedEmail() { return expectedEmail; }
    public UUID getIssuedByUserId() { return issuedByUserId; }
    public AccountTier getIssuedByTier() { return issuedByTier; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public UUID getConsumedByUserId() { return consumedByUserId; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isPending(Instant now) {
        return consumedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    public void consume(UUID userId, Instant now) {
        this.consumedAt = now;
        this.consumedByUserId = userId;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public String status(Instant now) {
        if (consumedAt != null) return "CONSUMED";
        if (revokedAt != null) return "REVOKED";
        if (!expiresAt.isAfter(now)) return "EXPIRED";
        return "PENDING";
    }
}
