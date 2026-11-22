package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class IdentityService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_LOGIN_FAILED_ATTEMPTS = 5;
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;
    private static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(5);
    private static final String FALLBACK_DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOHi7q8xI5J7v8P4bM1p5P6sV9z6X1K6W";
    static final String DEFAULT_WORKSPACE_NAME = "Default Workspace";

    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final AuthRefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final AuthProperties properties;
    private final TotpService totpService;
    private final String dummyPasswordHash;
    private final Clock clock = Clock.systemUTC();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditService audit;

    public IdentityService(AppUserRepository users, OrganizationRepository organizations, OrganizationMemberRepository members,
                           AuthRefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                           AccessTokenService accessTokens, AuthProperties properties, TotpService totpService) {
        this.users = users;
        this.organizations = organizations;
        this.members = members;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.totpService = totpService;
        String computed = passwordEncoder.encode("dummy-timing-" + UUID.randomUUID());
        this.dummyPasswordHash = computed != null ? computed : FALLBACK_DUMMY_HASH;
    }

    @Transactional
    public synchronized AppUser bootstrap(String email, String password) {
        if (users.count() != 0) throw new ApiException(HttpStatus.CONFLICT, "BOOTSTRAP_ALREADY_COMPLETED", "The first platform administrator already exists.");
        validatePasswordByteLength(password);
        AppUser administrator = users.save(new AppUser(email, passwordEncoder.encode(password), true, AccountTier.SYSTEM_ADMIN, null));
        Organization workspace = organizations.findByName(DEFAULT_WORKSPACE_NAME)
                .orElseGet(() -> organizations.save(new Organization(DEFAULT_WORKSPACE_NAME)));
        members.save(new OrganizationMember(workspace.getId(), administrator.getId(), OrganizationRole.ORGANIZATION_ADMIN));
        if (audit != null) {
            audit.record(workspace.getId(), administrator.getId(), "PLATFORM_BOOTSTRAPPED", "APP_USER", administrator.getId(),
                    Map.of("email", administrator.getEmail()));
        }
        return administrator;
    }

    @Transactional
    public AppUser createUser(String email, String password, boolean platformAdmin) {
        return createUser(email, password, platformAdmin,
                platformAdmin ? AccountTier.SYSTEM_ADMIN : AccountTier.GENERAL_USER, null);
    }

    @Transactional
    public AppUser createUser(String email, String password, boolean platformAdmin, AccountTier accountTier, UUID invitedByUserId) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "A user with that email already exists.");
        validatePasswordByteLength(password);
        AccountTier resolvedTier = platformAdmin ? AccountTier.SYSTEM_ADMIN : (accountTier == null ? AccountTier.GENERAL_USER : accountTier);
        return users.save(new AppUser(normalized, passwordEncoder.encode(password), platformAdmin, resolvedTier, invitedByUserId));
    }

    @Transactional
    public OrganizationMember grantMembership(UUID organizationId, UUID userId, OrganizationRole role) {
        if (!organizations.existsById(organizationId)) throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist.");
        AppUser user = users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist."));
        if (role == OrganizationRole.ORGANIZATION_ADMIN && !user.isPlatformAdmin() && user.getAccountTier() != AccountTier.PROJECT_DIRECTOR) {
            user.promoteToProjectDirector();
            users.save(user);
        }
        return members.save(new OrganizationMember(organizationId, userId, role));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session login(String email, String password) {
        return login(email, password, null, null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session login(String email, String password, String totpCode, String recoveryCode) {
        Instant now = clock.instant();
        String normalizedEmail = normalize(email);
        boolean oversizedPassword = password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_BYTES;
        String candidatePassword = oversizedPassword ? "oversized-password-placeholder" : password;
        AppUser user = users.findByEmailForUpdate(normalizedEmail).orElse(null);
        if (user == null) {
            passwordEncoder.matches(candidatePassword, dummyPasswordHash);
            throw invalidCredentials();
        }
        if (user.isLoginLocked(now)) {
            passwordEncoder.matches(candidatePassword, dummyPasswordHash);
            if (audit != null) {
                audit.record(null, user.getId(), "LOGIN_BLOCKED_LOCKED", "APP_USER", user.getId(),
                        Map.of("email", user.getEmail()));
            }
            throw accountLocked();
        }
        if (user.getLoginLockedUntil() != null) {
            user.clearLoginLockout();
        }
        if (!user.isEnabled()) {
            passwordEncoder.matches(candidatePassword, user.getPasswordHash());
            throw invalidCredentials();
        }
        if (oversizedPassword) {
            passwordEncoder.matches(candidatePassword, dummyPasswordHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(candidatePassword, user.getPasswordHash())) {
            boolean lockedNow = user.recordLoginFailure(MAX_LOGIN_FAILED_ATTEMPTS, LOGIN_LOCK_DURATION, now);
            users.saveAndFlush(user);
            if (audit != null) {
                audit.record(null, user.getId(), lockedNow ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", "APP_USER", user.getId(),
                        Map.of("email", user.getEmail(), "failedAttempts", user.getLoginFailedAttempts()));
            }
            if (lockedNow) {
                throw accountLocked();
            }
            throw invalidCredentials();
        }
        if (user.getLoginFailedAttempts() > 0 || user.getLoginLockedUntil() != null) {
            user.clearLoginLockout();
            users.saveAndFlush(user);
        }

        String suppliedCode = (totpCode != null && !totpCode.isBlank()) ? totpCode
                : (recoveryCode != null && !recoveryCode.isBlank() ? recoveryCode : null);
        boolean mfaVerified = false;
        if (user.isTotpEnabled() && suppliedCode != null) {
            totpService.verify(user.getId(), suppliedCode);
            mfaVerified = true;
            user = users.findById(user.getId()).orElse(user);
        }
        return issueSession(user, mfaVerified);
    }

    @Transactional
    public Session issueInitialSession(AppUser user) {
        return issueSession(user, false);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public EnrollmentResult enrollTotp(UUID userId, UUID sessionId, String code) {
        AuthRefreshToken currentSession = requireUsableSessionForUpdate(userId, sessionId);
        List<String> recoveryCodes = totpService.enroll(userId, code);
        AppUser user = getUser(userId);
        currentSession.revoke();
        refreshTokens.save(currentSession);
        revokeSessions(userId, true);
        Session upgraded = issueSession(user, true);
        return new EnrollmentResult(upgraded, recoveryCodes);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session verifyTotp(UUID userId, UUID sessionId, String code) {
        AuthRefreshToken currentSession = requireUsableSessionForUpdate(userId, sessionId);
        totpService.verify(userId, code);
        AppUser user = getUser(userId);
        currentSession.revoke();
        refreshTokens.save(currentSession);
        revokeSessions(userId, false);
        return issueSession(user, true);
    }

    @Transactional
    public Session refresh(String rawToken) {
        Instant now = clock.instant();
        AuthRefreshToken token = refreshTokens.findByTokenHashForUpdate(hashRefresh(rawToken)).orElseThrow(this::invalidCredentials);
        if (!token.isUsable(now)) throw invalidCredentials();
        AppUser user = users.findById(token.getUserId()).orElseThrow(this::invalidCredentials);
        if (!user.isEnabled()) throw invalidCredentials();
        boolean preserveMfaVerified = user.isTotpEnabled() && token.isMfaVerified();
        Instant inheritedPreMfaDeadline = preserveMfaVerified ? null : token.getExpiresAt();
        token.revoke();
        return issueSession(user, preserveMfaVerified, inheritedPreMfaDeadline);
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHashForUpdate(hashRefresh(rawToken)).ifPresent(AuthRefreshToken::revoke);
    }

    @Transactional
    public void logoutSession(UUID sessionId) {
        if (sessionId == null) return;
        refreshTokens.findByIdForUpdate(sessionId).ifPresent(AuthRefreshToken::revoke);
    }

    private void validatePasswordByteLength(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD_LENGTH",
                    "Password cannot exceed " + MAX_BCRYPT_PASSWORD_BYTES + " UTF-8 bytes.");
        }
    }

    public AppUser getUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist."));
    }

    private AuthRefreshToken requireUsableSessionForUpdate(UUID userId, UUID sessionId) {
        if (userId == null || sessionId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "An authenticated session is required.");
        }
        AuthRefreshToken session = refreshTokens.findByIdForUpdate(sessionId)
                .filter(item -> item.isUsable(Instant.now()) && item.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "The session is unavailable."));
        AppUser user = users.findById(userId).filter(AppUser::isEnabled)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "The account is unavailable."));
        if (!user.isEnabled()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "The account is unavailable.");
        }
        return session;
    }

    private Session issueSession(AppUser user, boolean mfaVerified) {
        return issueSession(user, mfaVerified, null);
    }

    private Session issueSession(AppUser user, boolean mfaVerified, Instant maxExpiresAt) {
        String rawRefresh = randomHex(48);
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(effectiveRefreshTtlSeconds(mfaVerified));
        if (maxExpiresAt != null && maxExpiresAt.isBefore(expiresAt)) {
            expiresAt = maxExpiresAt;
        }
        AuthRefreshToken session = refreshTokens.save(new AuthRefreshToken(
                user.getId(),
                hashRefresh(rawRefresh),
                expiresAt,
                mfaVerified
        ));
        return new Session(accessTokens.issue(user, session.getId(), mfaVerified), rawRefresh, user, mfaVerified);
    }

    public long effectiveRefreshTtlSeconds(boolean mfaVerified) {
        return mfaVerified
                ? properties.refreshTokenSeconds()
                : Math.min(properties.refreshTokenSeconds(), properties.preMfaRefreshTokenSeconds());
    }

    private void revokeSessions(UUID userId, boolean includeVerified) {
        List<AuthRefreshToken> active = refreshTokens.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(token -> includeVerified || !token.isMfaVerified())
                .toList();
        if (!active.isEmpty()) {
            active.forEach(AuthRefreshToken::revoke);
            refreshTokens.saveAll(active);
        }
    }

    private String hashRefresh(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.refreshPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash refresh token", exception);
        }
    }

    private String randomHex(int bytes) { byte[] data = new byte[bytes]; RANDOM.nextBytes(data); return HexFormat.of().formatHex(data); }
    private String normalize(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    private ApiException invalidCredentials() { return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "The email or password is invalid."); }
    private ApiException accountLocked() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED",
                "Too many failed login attempts. Try again in 5 minutes.");
    }

    public record Session(String accessToken, String refreshToken, AppUser user, boolean mfaVerified) {
        public String mfaState() {
            if (!user.isTotpEnabled()) return "ENROLLMENT_REQUIRED";
            return mfaVerified ? "VERIFIED" : "VERIFICATION_REQUIRED";
        }
    }

    public record EnrollmentResult(Session session, List<String> recoveryCodes) { }
}
