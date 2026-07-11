package com.aiconnect.llmgateway.identity;

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
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class IdentityService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final AuthRefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final AuthProperties properties;

    public IdentityService(AppUserRepository users, OrganizationRepository organizations, OrganizationMemberRepository members,
                           AuthRefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder, AccessTokenService accessTokens, AuthProperties properties) {
        this.users = users; this.organizations = organizations; this.members = members; this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder; this.accessTokens = accessTokens; this.properties = properties;
    }
    @Transactional
    public AppUser bootstrap(String email, String password) {
        if (users.count() != 0) throw new ApiException(HttpStatus.CONFLICT, "BOOTSTRAP_ALREADY_COMPLETED", "The first platform administrator already exists.");
        return users.save(new AppUser(email, passwordEncoder.encode(password), true));
    }
    @Transactional
    public AppUser createUser(String email, String password, boolean platformAdmin) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "A user with that email already exists.");
        return users.save(new AppUser(normalized, passwordEncoder.encode(password), platformAdmin));
    }
    @Transactional
    public OrganizationMember grantMembership(UUID organizationId, UUID userId, OrganizationRole role) {
        if (!organizations.existsById(organizationId)) throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist.");
        if (!users.existsById(userId)) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist.");
        return members.save(new OrganizationMember(organizationId, userId, role));
    }
    @Transactional
    public Session login(String email, String password) {
        AppUser user = users.findByEmail(normalize(email)).orElseThrow(() -> invalidCredentials());
        if (!user.isEnabled() || !passwordEncoder.matches(password, user.getPasswordHash())) throw invalidCredentials();
        return issueSession(user);
    }
    @Transactional
    public Session refresh(String rawToken) {
        AuthRefreshToken token = refreshTokens.findByTokenHashForUpdate(hashRefresh(rawToken)).orElseThrow(this::invalidCredentials);
        if (!token.isUsable(Instant.now())) throw invalidCredentials();
        AppUser user = users.findById(token.getUserId()).orElseThrow(this::invalidCredentials);
        if (!user.isEnabled()) throw invalidCredentials();
        token.revoke();
        return issueSession(user);
    }
    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHashForUpdate(hashRefresh(rawToken)).ifPresent(AuthRefreshToken::revoke);
    }
    public AppUser getUser(UUID userId) { return users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist.")); }
    private Session issueSession(AppUser user) {
        String rawRefresh = randomHex(48);
        refreshTokens.save(new AuthRefreshToken(user.getId(), hashRefresh(rawRefresh), Instant.now().plusSeconds(properties.refreshTokenSeconds())));
        return new Session(accessTokens.issue(user), rawRefresh, user);
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
    public record Session(String accessToken, String refreshToken, AppUser user) { }
}
