package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class TotpService {
    static final long TIME_STEP_SECONDS = 30L;
    static final int DIGITS = 6;
    static final int ALLOWED_WINDOW_STEPS = 1;
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(5);
    static final int RECOVERY_CODE_COUNT = 8;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final AppUserRepository users;
    private final UserTotpRecoveryCodeRepository recoveryCodes;
    private final AuthRefreshTokenRepository refreshTokens;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final AuthProperties authProperties;
    private final String issuer;
    private final Clock clock;

    @Autowired
    public TotpService(AppUserRepository users,
                       UserTotpRecoveryCodeRepository recoveryCodes,
                       AuthRefreshTokenRepository refreshTokens,
                       SecretCipher cipher,
                       AuditService audit,
                       AuthProperties authProperties,
                       @Value("${aiconnect.auth.totp-issuer:AIConnect}") String issuer) {
        this(users, recoveryCodes, refreshTokens, cipher, audit, authProperties, issuer, Clock.systemUTC());
    }

    TotpService(AppUserRepository users,
                UserTotpRecoveryCodeRepository recoveryCodes,
                AuthRefreshTokenRepository refreshTokens,
                SecretCipher cipher,
                AuditService audit,
                AuthProperties authProperties,
                String issuer,
                Clock clock) {
        this.users = users;
        this.recoveryCodes = recoveryCodes;
        this.refreshTokens = refreshTokens;
        this.cipher = cipher;
        this.audit = audit;
        this.authProperties = authProperties;
        this.issuer = (issuer == null || issuer.isBlank()) ? "AIConnect" : issuer.trim();
        this.clock = clock;
    }

    @Transactional
    public TotpSetup beginSetup(UUID userId) {
        AppUser user = requireUserForUpdate(userId);
        if (user.isTotpEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "TOTP_ALREADY_ENABLED",
                    "Two-factor authentication is already enabled for this account.");
        }
        String secret = generateSecret();
        user.assignPendingTotpSecret(cipher.encrypt(secret));
        users.save(user);
        return new TotpSetup(secret, buildOtpauthUri(user.getEmail(), secret), issuer, user.getEmail());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public List<String> enroll(UUID userId, String rawCode) {
        Instant now = clock.instant();
        AppUser user = requireUserForUpdate(userId);
        if (user.isTotpEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "TOTP_ALREADY_ENABLED",
                    "Two-factor authentication is already enabled for this account.");
        }
        ensureNotLocked(user, now);
        if (user.getTotpPendingSecretEncrypted() == null || user.getTotpPendingSecretEncrypted().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOTP_SETUP_REQUIRED",
                    "Generate a TOTP setup secret before enrolling.");
        }
        String secret = cipher.decrypt(user.getTotpPendingSecretEncrypted());
        String normalizedCode = normalizeDigits(rawCode);
        if (normalizedCode == null) {
            recordFailureAndThrow(user, now, "INVALID_TOTP_CODE", "The authentication code must be a 6-digit number.");
        }
        Long matchedStep = findMatchingStep(secret, normalizedCode, now);
        if (matchedStep == null) {
            recordFailureAndThrow(user, now, "INVALID_TOTP_CODE", "The authentication code is invalid.");
        }

        user.activateTotp(matchedStep, now);
        users.save(user);

        recoveryCodes.deleteByUserId(userId);
        recoveryCodes.flush();
        List<String> plaintextRecoveryCodes = generateRecoveryCodes();
        List<UserTotpRecoveryCode> entities = plaintextRecoveryCodes.stream()
                .map(code -> new UserTotpRecoveryCode(userId, hashRecoveryCode(normalizeRecoveryCode(code))))
                .toList();
        recoveryCodes.saveAll(entities);

        audit.record(null, userId, "TOTP_ENROLLED", "APP_USER", userId, Map.of("email", user.getEmail()));
        return plaintextRecoveryCodes;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public VerificationOutcome verify(UUID userId, String rawCode) {
        Instant now = clock.instant();
        AppUser user = requireUserForUpdate(userId);
        if (!user.isTotpEnabled() || user.getTotpSecretEncrypted() == null || user.getTotpSecretEncrypted().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "TOTP_NOT_ENROLLED",
                    "Two-factor authentication is not enrolled for this account.");
        }
        ensureNotLocked(user, now);

        String trimmed = rawCode == null ? "" : rawCode.trim();
        String digits = normalizeDigits(trimmed);
        if (digits != null) {
            String secret = cipher.decrypt(user.getTotpSecretEncrypted());
            Long matchedStep = findMatchingStep(secret, digits, now);
            if (matchedStep == null) {
                recordFailureAndThrow(user, now, "INVALID_TOTP_CODE", "The authentication code is invalid.");
            }
            if (user.getTotpLastUsedStep() != null && matchedStep <= user.getTotpLastUsedStep()) {
                recordFailureAndThrow(user, now, "TOTP_CODE_ALREADY_USED",
                        "This authentication code has already been used. Wait for the next code.");
            }
            user.recordTotpSuccess(matchedStep);
            users.save(user);
            audit.record(null, userId, "TOTP_VERIFIED", "APP_USER", userId, Map.of("method", "TOTP"));
            return VerificationOutcome.TOTP;
        }

        String normalizedRecovery = normalizeRecoveryCode(trimmed);
        if (normalizedRecovery.length() == 12) {
            UserTotpRecoveryCode match = recoveryCodes
                    .findByUserIdAndCodeHashAndUsedAtIsNull(userId, hashRecoveryCode(normalizedRecovery))
                    .orElse(null);
            if (match != null) {
                match.markUsed(now);
                recoveryCodes.save(match);
                user.recordTotpSuccess(null);
                users.save(user);
                audit.record(null, userId, "TOTP_VERIFIED", "APP_USER", userId, Map.of("method", "RECOVERY_CODE"));
                return VerificationOutcome.RECOVERY_CODE;
            }
        }

        recordFailureAndThrow(user, now, "INVALID_TOTP_CODE", "The authentication code or recovery code is invalid.");
        return VerificationOutcome.TOTP; // unreachable
    }

    @Transactional
    public void resetTotp(UUID targetUserId, UUID actorUserId, UUID organizationId) {
        AppUser user = requireUserForUpdate(targetUserId);
        user.resetTotp();
        users.save(user);
        recoveryCodes.deleteByUserId(targetUserId);
        List<AuthRefreshToken> activeTokens = refreshTokens.findByUserIdAndRevokedAtIsNull(targetUserId);
        activeTokens.forEach(AuthRefreshToken::revoke);
        refreshTokens.saveAll(activeTokens);
        audit.record(organizationId, actorUserId, "USER_TOTP_RESET", "APP_USER", targetUserId,
                Map.of("email", user.getEmail()));
    }

    public String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public String buildOtpauthUri(String email, String base32Secret) {
        String encodedIssuer = urlEncode(issuer);
        String encodedEmail = urlEncode(email);
        return "otpauth://totp/" + encodedIssuer + ":" + encodedEmail
                + "?secret=" + base32Secret
                + "&issuer=" + encodedIssuer
                + "&algorithm=SHA1&digits=" + DIGITS
                + "&period=" + TIME_STEP_SECONDS;
    }

    public String generateCode(String base32Secret, Instant instant) {
        return generateCodeForStep(base32Secret, instant.getEpochSecond() / TIME_STEP_SECONDS);
    }

    public String generateCodeForStep(String base32Secret, long step) {
        byte[] key = decodeBase32(base32Secret);
        byte[] counter = ByteBuffer.allocate(8).putLong(step).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not compute TOTP code", exception);
        }
    }

    private Long findMatchingStep(String base32Secret, String digits, Instant now) {
        long currentStep = now.getEpochSecond() / TIME_STEP_SECONDS;
        byte[] supplied = digits.getBytes(StandardCharsets.US_ASCII);
        for (long delta = -ALLOWED_WINDOW_STEPS; delta <= ALLOWED_WINDOW_STEPS; delta++) {
            long candidateStep = currentStep + delta;
            if (candidateStep < 0) continue;
            byte[] expected = generateCodeForStep(base32Secret, candidateStep).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, supplied)) {
                return candidateStep;
            }
        }
        return null;
    }

    private void ensureNotLocked(AppUser user, Instant now) {
        if (user.getTotpLockedUntil() != null) {
            if (user.getTotpLockedUntil().isAfter(now)) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOTP_LOCKED",
                        "Too many failed two-factor authentication attempts. Try again in 5 minutes.");
            }
            user.clearTotpLockout();
        }
    }

    private void recordFailureAndThrow(AppUser user, Instant now, String code, String message) {
        boolean lockedNow = user.recordTotpFailure(MAX_FAILED_ATTEMPTS, LOCK_DURATION, now);
        users.saveAndFlush(user);
        if (lockedNow) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOTP_LOCKED",
                    "Too many failed two-factor authentication attempts. Try again in 5 minutes.");
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    private AppUser requireUserForUpdate(UUID userId) {
        return users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The user does not exist."));
    }

    private String normalizeDigits(String raw) {
        if (raw == null) return null;
        String compact = raw.trim().replaceAll("\\s+", "");
        return compact.matches("\\d{6}") ? compact : null;
    }

    private String normalizeRecoveryCode(String raw) {
        if (raw == null) return "";
        return raw.trim().replace("-", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            StringBuilder sb = new StringBuilder(14);
            for (int c = 0; c < 12; c++) {
                if (c > 0 && c % 4 == 0) sb.append('-');
                sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    private String hashRecoveryCode(String normalized) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authProperties.refreshPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("totp-recovery:" + normalized).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash recovery code", exception);
        }
    }

    private static String encodeBase32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                bitsLeft -= 5;
                out.append(BASE32_ALPHABET.charAt(index));
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            out.append(BASE32_ALPHABET.charAt(index));
        }
        return out.toString();
    }

    static byte[] decodeBase32(String base32) {
        String normalized = base32.trim().replace("=", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        byte[] out = new byte[(normalized.length() * 5) / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int pos = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            int val = BASE32_ALPHABET.indexOf(ch);
            if (val < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + ch);
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[pos++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return Arrays.copyOf(out, pos);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public enum VerificationOutcome {
        TOTP,
        RECOVERY_CODE
    }

    public record TotpSetup(String secret, String otpauthUri, String issuer, String email) { }
}
