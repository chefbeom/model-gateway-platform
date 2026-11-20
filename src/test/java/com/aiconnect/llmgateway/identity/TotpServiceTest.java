package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TotpServiceTest {
    private static final String RFC6238_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesOfficialRfc6238Sha1Vectors() {
        TotpService service = createService(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC),
                mock(AppUserRepository.class), mock(UserTotpRecoveryCodeRepository.class),
                mock(AuthRefreshTokenRepository.class));

        assertThat(service.generateCode(RFC6238_SECRET_BASE32, Instant.ofEpochSecond(59L))).isEqualTo("287082");
        assertThat(service.generateCode(RFC6238_SECRET_BASE32, Instant.ofEpochSecond(1111111109L))).isEqualTo("081804");
        assertThat(service.generateCode(RFC6238_SECRET_BASE32, Instant.ofEpochSecond(1111111111L))).isEqualTo("050471");
        assertThat(service.generateCode(RFC6238_SECRET_BASE32, Instant.ofEpochSecond(1234567890L))).isEqualTo("005924");
        assertThat(service.generateCode(RFC6238_SECRET_BASE32, Instant.ofEpochSecond(2000000000L))).isEqualTo("279037");
        assertThat(service.generateCode(RFC6238_SECRET_BASE32, Instant.ofEpochSecond(20000000000L))).isEqualTo("353130");
    }

    @Test
    void encryptsSecretAcceptsPlusMinusOneStepBlocksReplayAndConsumesRecoveryCodes() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(3_000));
        UUID userId = UUID.randomUUID();
        AppUser user = new AppUser("user@example.com", "pw-hash", false);
        ReflectionTestUtils.setField(user, "id", userId);

        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        List<UserTotpRecoveryCode> storedRecovery = new ArrayList<>();
        UserTotpRecoveryCodeRepository recoveryRepo = mock(UserTotpRecoveryCodeRepository.class);
        when(recoveryRepo.saveAll(any())).thenAnswer(inv -> {
            Iterable<UserTotpRecoveryCode> items = inv.getArgument(0);
            storedRecovery.clear();
            items.forEach(storedRecovery::add);
            return storedRecovery;
        });
        when(recoveryRepo.findByUserIdAndCodeHashAndUsedAtIsNull(eq(userId), any())).thenAnswer(inv -> {
            String hash = inv.getArgument(1);
            return storedRecovery.stream().filter(c -> c.isUnused() && c.getCodeHash().equals(hash)).findFirst();
        });

        AuthRefreshTokenRepository refreshRepo = mock(AuthRefreshTokenRepository.class);
        TotpService service = createService(clock, users, recoveryRepo, refreshRepo);

        TotpService.TotpSetup setup = service.beginSetup(userId);
        assertThat(setup.secret()).hasSize(32);
        assertThat(setup.otpauthUri()).startsWith("otpauth://totp/AIConnect:user%40example.com?secret=" + setup.secret());
        assertThat(user.getTotpPendingSecretEncrypted()).isNotBlank().doesNotContain(setup.secret());

        String stepMinusTwo = service.generateCode(setup.secret(), clock.instant().minusSeconds(60));
        assertThatThrownBy(() -> service.enroll(userId, stepMinusTwo))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_TOTP_CODE");

        String stepMinusOne = service.generateCode(setup.secret(), clock.instant().minusSeconds(30));
        List<String> recoveryCodes = service.enroll(userId, stepMinusOne);
        assertThat(recoveryCodes).hasSize(8);
        assertThat(user.isTotpEnabled()).isTrue();
        assertThat(user.getTotpSecretEncrypted()).isNotBlank().doesNotContain(setup.secret());
        assertThat(user.getTotpPendingSecretEncrypted()).isNull();
        assertThat(storedRecovery).hasSize(8);
        for (int i = 0; i < 8; i++) {
            assertThat(storedRecovery.get(i).getCodeHash()).doesNotContain(recoveryCodes.get(i));
        }

        assertThatThrownBy(() -> service.beginSetup(userId))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("TOTP_ALREADY_ENABLED");

        assertThatThrownBy(() -> service.verify(userId, stepMinusOne))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("TOTP_CODE_ALREADY_USED");

        String stepPlusOne = service.generateCode(setup.secret(), clock.instant().plusSeconds(30));
        assertThat(service.verify(userId, stepPlusOne)).isEqualTo(TotpService.VerificationOutcome.TOTP);

        String recovery = recoveryCodes.get(0);
        assertThat(service.verify(userId, recovery.toLowerCase().replace("-", " "))).isEqualTo(TotpService.VerificationOutcome.RECOVERY_CODE);
        assertThatThrownBy(() -> service.verify(userId, recovery))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_TOTP_CODE");
    }

    @Test
    void locksOutAfterFiveConsecutiveFailuresForFiveMinutesAndResetsCleanly() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(6_000));
        UUID userId = UUID.randomUUID();
        AppUser user = new AppUser("lock@example.com", "pw-hash", false);
        ReflectionTestUtils.setField(user, "id", userId);

        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UserTotpRecoveryCodeRepository recoveryRepo = mock(UserTotpRecoveryCodeRepository.class);
        AuthRefreshToken activeToken = new AuthRefreshToken(userId, "h", clock.instant().plusSeconds(3600), true);
        AuthRefreshTokenRepository refreshRepo = mock(AuthRefreshTokenRepository.class);
        when(refreshRepo.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(activeToken));

        TotpService service = createService(clock, users, recoveryRepo, refreshRepo);
        TotpService.TotpSetup setup = service.beginSetup(userId);
        String enrollCode = service.generateCode(setup.secret(), clock.instant());
        service.enroll(userId, enrollCode);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.verify(userId, "000000"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }

        assertThatThrownBy(() -> service.verify(userId, "000000"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(api.getCode()).isEqualTo("TOTP_LOCKED");
                });

        clock.advance(Duration.ofSeconds(60));
        String validLaterCode = service.generateCode(setup.secret(), clock.instant());
        assertThatThrownBy(() -> service.verify(userId, validLaterCode))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("TOTP_LOCKED");

        clock.advance(Duration.ofMinutes(5));
        String validAfterExpiry = service.generateCode(setup.secret(), clock.instant());
        assertThat(service.verify(userId, validAfterExpiry)).isEqualTo(TotpService.VerificationOutcome.TOTP);
        assertThat(user.getTotpFailedAttempts()).isZero();
        assertThat(user.getTotpLockedUntil()).isNull();

        clearInvocations(recoveryRepo);
        service.resetTotp(userId, UUID.randomUUID(), null);
        assertThat(user.isTotpEnabled()).isFalse();
        assertThat(user.getTotpSecretEncrypted()).isNull();
        assertThat(activeToken.isUsable(clock.instant())).isFalse();
        verify(recoveryRepo).deleteByUserId(userId);
    }

    private TotpService createService(Clock clock, AppUserRepository users,
                                      UserTotpRecoveryCodeRepository recoveryRepo,
                                      AuthRefreshTokenRepository refreshRepo) {
        SecretCipher cipher = new SecretCipher(new GatewayProperties("admin", "pepper", "unit-test-encryption-key-01234567", 30_000, 1_000, 5_000));
        AuthProperties authProps = new AuthProperties("signing-key-unit-test-0123456789", "refresh-pepper-unit-test-0123456", 900, 3600);
        return new TotpService(users, recoveryRepo, refreshRepo, cipher, mock(AuditService.class), authProps, "AIConnect", clock);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant initial) { this.current = initial; }
        void advance(Duration duration) { this.current = this.current.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
