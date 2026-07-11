package com.aiconnect.llmgateway.identity;

import jakarta.persistence.*;
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
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    protected AppUser() { }
    public AppUser(String email, String passwordHash, boolean platformAdmin) {
        this.email = normalize(email); this.passwordHash = passwordHash; this.platformAdmin = platformAdmin;
    }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    private static String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isPlatformAdmin() { return platformAdmin; }
    public boolean isEnabled() { return enabled; }
}
