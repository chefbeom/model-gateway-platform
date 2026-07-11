package com.aiconnect.llmgateway.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_refresh_token")
public class AuthRefreshToken {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID userId;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String tokenHash;
    @Column(nullable = false) private Instant expiresAt;
    private Instant revokedAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    protected AuthRefreshToken() { }
    public AuthRefreshToken(UUID userId, String tokenHash, Instant expiresAt) { this.userId = userId; this.tokenHash = tokenHash; this.expiresAt = expiresAt; }
    public boolean isUsable(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }
    public void revoke() { revokedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
}
