package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.identity.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionAuthenticationFilterTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentAccountStateAndRoleOverrideTokenClaims() throws Exception {
        UUID id = UUID.randomUUID();
        AccessTokenService tokens = mock(AccessTokenService.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AuthRefreshTokenRepository sessions = mock(AuthRefreshTokenRepository.class);
        UUID sessionId = UUID.randomUUID();
        when(tokens.parseSession("token")).thenReturn(new AccessTokenService.ParsedSession(new AuthPrincipal(id, "old@example.com", true), sessionId));
        AuthRefreshToken session = new AuthRefreshToken(id, "hash", Instant.now().plusSeconds(60), true);
        ReflectionTestUtils.setField(session, "id", sessionId);
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        when(tokens.parse("token")).thenReturn(new AuthPrincipal(id, "old@example.com", true));
        AppUser user = new AppUser("current@example.com", "hash", false);
        ReflectionTestUtils.setField(user, "id", id);
        user.assignPendingTotpSecret("enc");
        user.activateTotp(10L, Instant.now());
        when(users.findById(id)).thenReturn(Optional.of(user));
        var request = new MockHttpServletRequest("GET", "/api/admin/organizations");
        request.addHeader("Authorization", "Bearer token");
        var response = new MockHttpServletResponse();
        new SessionAuthenticationFilter(tokens, users, sessions).doFilter(request, response, (req, res) -> {
            AuthPrincipal current = (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertThat(current.platformAdmin()).isFalse();
            assertThat(current.email()).isEqualTo("current@example.com");
            assertThat(current.totpEnabled()).isTrue();
            assertThat(current.mfaVerified()).isTrue();
        });
        assertThat(response.getStatus()).isEqualTo(200);

        ReflectionTestUtils.setField(user, "enabled", false);
        var rejected = new MockHttpServletResponse();
        new SessionAuthenticationFilter(tokens, users, sessions).doFilter(request, rejected, (req, res) -> { throw new AssertionError(); });
        assertThat(rejected.getStatus()).isEqualTo(401);

        ReflectionTestUtils.setField(user, "enabled", true);
        session.revoke();
        var loggedOut = new MockHttpServletResponse();
        new SessionAuthenticationFilter(tokens, users, sessions).doFilter(request, loggedOut, (req, res) -> { throw new AssertionError(); });
        assertThat(loggedOut.getStatus()).isEqualTo(401);
    }

    @Test
    void blocksUnenrolledAndUnverifiedSessionsOutsideAuthEndpoints() throws Exception {
        UUID id = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        AccessTokenService tokens = mock(AccessTokenService.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AuthRefreshTokenRepository sessions = mock(AuthRefreshTokenRepository.class);
        when(tokens.parseSession("token")).thenReturn(new AccessTokenService.ParsedSession(new AuthPrincipal(id, "u@example.com", false), sessionId));

        AuthRefreshToken session = new AuthRefreshToken(id, "hash", Instant.now().plusSeconds(60), false);
        ReflectionTestUtils.setField(session, "id", sessionId);
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));

        AppUser user = new AppUser("u@example.com", "hash", false);
        ReflectionTestUtils.setField(user, "id", id);
        when(users.findById(id)).thenReturn(Optional.of(user));

        SessionAuthenticationFilter filter = new SessionAuthenticationFilter(tokens, users, sessions);

        var adminReq = new MockHttpServletRequest("GET", "/api/admin/organizations");
        adminReq.addHeader("Authorization", "Bearer token");
        var unenrolledRes = new MockHttpServletResponse();
        filter.doFilter(adminReq, unenrolledRes, (req, res) -> { throw new AssertionError(); });
        assertThat(unenrolledRes.getStatus()).isEqualTo(403);
        assertThat(unenrolledRes.getContentAsString()).contains("TOTP_ENROLLMENT_REQUIRED");

        var setupReq = new MockHttpServletRequest("POST", "/api/auth/totp/setup");
        setupReq.addHeader("Authorization", "Bearer token");
        var setupRes = new MockHttpServletResponse();
        filter.doFilter(setupReq, setupRes, (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isInstanceOf(AuthPrincipal.class);
            assertThat(req.getAttribute("aiconnect.session-id")).isEqualTo(sessionId);
        });
        assertThat(setupRes.getStatus()).isEqualTo(200);

        user.assignPendingTotpSecret("enc");
        user.activateTotp(10L, Instant.now());
        var unverifiedRes = new MockHttpServletResponse();
        filter.doFilter(adminReq, unverifiedRes, (req, res) -> { throw new AssertionError(); });
        assertThat(unverifiedRes.getStatus()).isEqualTo(403);
        assertThat(unverifiedRes.getContentAsString()).contains("TOTP_VERIFICATION_REQUIRED");

        var verifyReq = new MockHttpServletRequest("POST", "/api/auth/totp/verify");
        verifyReq.addHeader("Authorization", "Bearer token");
        var verifyRes = new MockHttpServletResponse();
        filter.doFilter(verifyReq, verifyRes, (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isInstanceOf(AuthPrincipal.class);
        });
        assertThat(verifyRes.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotInterfereWithApiKeyOrAdminTokenRequests() throws Exception {
        AccessTokenService tokens = mock(AccessTokenService.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AuthRefreshTokenRepository sessions = mock(AuthRefreshTokenRepository.class);
        SessionAuthenticationFilter filter = new SessionAuthenticationFilter(tokens, users, sessions);

        var apiKeyReq = new MockHttpServletRequest("POST", "/api/portal/something");
        apiKeyReq.addHeader("Authorization", "Bearer sk_llmg_12345");
        var apiKeyRes = new MockHttpServletResponse();
        final boolean[] called = {false};
        filter.doFilter(apiKeyReq, apiKeyRes, (req, res) -> called[0] = true);
        assertThat(called[0]).isTrue();
        verifyNoInteractions(tokens);

        var adminTokenReq = new MockHttpServletRequest("GET", "/api/admin/platform/overview");
        adminTokenReq.addHeader("X-Admin-Token", "secret");
        var adminTokenRes = new MockHttpServletResponse();
        called[0] = false;
        filter.doFilter(adminTokenReq, adminTokenRes, (req, res) -> called[0] = true);
        assertThat(called[0]).isTrue();
        verifyNoInteractions(tokens);
    }
}
