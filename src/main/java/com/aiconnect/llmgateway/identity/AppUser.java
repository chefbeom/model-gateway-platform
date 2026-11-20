package com.aiconnect.llmgateway.identity;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, length = 320, unique = true) private String email;
    @Column(nullable = false, length = 100) private String passwordHash;
    @Column(nullable = false) private boolean platformAdmin;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AccountTier accountTier = AccountTier.GENERAL_USER;
    @Column(columnDefinition = "char(36)") private UUID invitedByUserId;
    @Column(columnDefinition = "text") private String totpSecretEncrypted;
    @Column(columnDefinition = "text") private String totpPendingSecretEncrypted;
    @Column(nullable = false) private boolean totpEnabled = false;
    private Instant totpEnrolledAt;
    private Long totpLastUsedStep;
    @Column(nullable = false) private int totpFailedAttempts = 0;
    private Instant totpLockedUntil;
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false) private boolean projectScopeEnforced = false;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected AppUser() { }

    public AppUser(String email, String passwordHash, boolean platformAdmin) {
        this(email, passwordHash, platformAdmin,
                platformAdmin ? AccountTier.SYSTEM_ADMIN : AccountTier.GENERAL_USER, null);
    }

    public AppUser(String email, String passwordHash, boolean platformAdmin, AccountTier accountTier, UUID invitedByUserId) {
        this.email = normalize(email);
        this.passwordHash = passwordHash;
        this.platformAdmin = platformAdmin;
        this.accountTier = platformAdmin ? AccountTier.SYSTEM_ADMIN : (accountTier == null ? AccountTier.GENERAL_USER : accountTier);
        this.invitedByUserId = invitedByUserId;
    }

    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    private static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isPlatformAdmin() { return platformAdmin; }
    public AccountTier getAccountTier() { return accountTier; }
    public UUID getInvitedByUserId() { return invitedByUserId; }
    public String getTotpSecretEncrypted() { return totpSecretEncrypted; }
    public String getTotpPendingSecretEncrypted() { return totpPendingSecretEncrypted; }
    public boolean isTotpEnabled() { return totpEnabled; }
    public Instant getTotpEnrolledAt() { return totpEnrolledAt; }
    public Long getTotpLastUsedStep() { return totpLastUsedStep; }
    public int getTotpFailedAttempts() { return totpFailedAttempts; }
    public Instant getTotpLockedUntil() { return totpLockedUntil; }
    public boolean isEnabled() { return enabled; }
    public boolean isProjectScopeEnforced() { return projectScopeEnforced; }
    public void enforceProjectScope() { projectScopeEnforced = true; }

    public void promoteToProjectDirector() {
        if (!platformAdmin) {
            this.accountTier = AccountTier.PROJECT_DIRECTOR;
        }
    }

    public void assignPendingTotpSecret(String encryptedSecret) {
        this.totpPendingSecretEncrypted = encryptedSecret;
    }

    public void activateTotp(long matchedStep, Instant enrolledAt) {
        this.totpSecretEncrypted = this.totpPendingSecretEncrypted;
        this.totpPendingSecretEncrypted = null;
        this.totpEnabled = true;
        this.totpEnrolledAt = enrolledAt;
        this.totpLastUsedStep = matchedStep;
        this.totpFailedAttempts = 0;
        this.totpLockedUntil = null;
    }

    public void recordTotpSuccess(Long matchedStep) {
        if (matchedStep != null) {
            this.totpLastUsedStep = matchedStep;
        }
        this.totpFailedAttempts = 0;
        this.totpLockedUntil = null;
    }

    public boolean recordTotpFailure(int maxAttempts, Duration lockDuration, Instant now) {
        this.totpFailedAttempts = this.totpFailedAttempts + 1;
        if (this.totpFailedAttempts >= maxAttempts) {
            this.totpLockedUntil = now.plus(lockDuration);
            return true;
        }
        return false;
    }

    public void clearTotpLockout() {
        this.totpFailedAttempts = 0;
        this.totpLockedUntil = null;
    }

    public void resetTotp() {
        this.totpSecretEncrypted = null;
        this.totpPendingSecretEncrypted = null;
        this.totpEnabled = false;
        this.totpEnrolledAt = null;
        this.totpLastUsedStep = null;
        this.totpFailedAttempts = 0;
        this.totpLockedUntil = null;
    }
}
