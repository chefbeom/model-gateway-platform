package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AccessTokenService {
    private final ObjectMapper objectMapper;
    private final AuthProperties properties;
    public AccessTokenService(ObjectMapper objectMapper, AuthProperties properties) { this.objectMapper = objectMapper; this.properties = properties; }

    public String issue(AppUser user) {
        return issue(user, null, false);
    }

    public String issue(AppUser user, UUID sessionId) {
        return issue(user, sessionId, false);
    }

    public String issue(AppUser user, UUID sessionId, boolean mfaVerified) {
        try {
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            long now = Instant.now().getEpochSecond();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", user.getId().toString());
            payload.put("email", user.getEmail());
            payload.put("platform_admin", user.isPlatformAdmin());
            payload.put("account_tier", AccountTier.effectiveFor(user).name());
            payload.put("totp_enabled", user.isTotpEnabled());
            payload.put("mfa_verified", mfaVerified);
            if (sessionId != null) payload.put("sid", sessionId.toString());
            payload.put("iat", now);
            payload.put("exp", now + properties.accessTokenSeconds());
            String encodedHeader = encode(objectMapper.writeValueAsBytes(header));
            String encodedPayload = encode(objectMapper.writeValueAsBytes(payload));
            return encodedHeader + "." + encodedPayload + "." + encode(hmac(encodedHeader + "." + encodedPayload));
        } catch (Exception exception) { throw new IllegalStateException("Could not issue an access token", exception); }
    }

    public AuthPrincipal parse(String token) {
        return parseClaims(token, false).principal();
    }

    public ParsedSession parseSession(String token) {
        return parseClaims(token, true);
    }

    private ParsedSession parseClaims(String token, boolean requireSession) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !MessageDigest.isEqual(decode(parts[2]), hmac(parts[0] + "." + parts[1]))) throw unauthorized();
            Map<String, Object> payload = objectMapper.readValue(decode(parts[1]), new TypeReference<>() { });
            long expiresAt = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= expiresAt) throw unauthorized();
            boolean platformAdmin = Boolean.TRUE.equals(payload.get("platform_admin"));
            AccountTier tier = parseTier(payload.get("account_tier"), platformAdmin);
            boolean totpEnabled = Boolean.TRUE.equals(payload.get("totp_enabled"));
            boolean mfaVerified = Boolean.TRUE.equals(payload.get("mfa_verified"));
            AuthPrincipal principal = new AuthPrincipal(
                    UUID.fromString((String) payload.get("sub")),
                    (String) payload.get("email"),
                    platformAdmin,
                    tier,
                    totpEnabled,
                    mfaVerified
            );
            UUID sessionId = payload.get("sid") == null ? null : UUID.fromString((String) payload.get("sid"));
            if (requireSession && sessionId == null) throw unauthorized();
            return new ParsedSession(principal, sessionId);
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw unauthorized(); }
    }

    private AccountTier parseTier(Object rawTier, boolean platformAdmin) {
        if (platformAdmin) return AccountTier.SYSTEM_ADMIN;
        if (rawTier instanceof String str && !str.isBlank()) {
            return AccountTier.valueOf(str);
        }
        return AccountTier.GENERAL_USER;
    }

    public record ParsedSession(AuthPrincipal principal, UUID sessionId) { }
    private ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "The access token is invalid or expired."); }
    private byte[] hmac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(properties.signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
    private String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
}
