package com.aiconnect.llmgateway.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_totp_recovery_code")
public class UserTotpRecoveryCode {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID userId;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String codeHash;
    private Instant usedAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();

    protected UserTotpRecoveryCode() { }

    public UserTotpRecoveryCode(UUID userId, String codeHash) {
        this.userId = userId;
        this.codeHash = codeHash;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isUnused() { return usedAt == null; }
    public void markUsed(Instant now) { this.usedAt = now; }
}
