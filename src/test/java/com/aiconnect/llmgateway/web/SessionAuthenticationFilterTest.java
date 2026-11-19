package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SessionAuthenticationFilterTest {
    @Test void currentAccountStateAndRoleOverrideTokenClaims() throws Exception {
        UUID id = UUID.randomUUID();
        AccessTokenService tokens = mock(AccessTokenService.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AuthRefreshTokenRepository sessions = mock(AuthRefreshTokenRepository.class);
        UUID sessionId = UUID.randomUUID();
        when(tokens.parseSession("token")).thenReturn(new AccessTokenService.ParsedSession(new AuthPrincipal(id, "old@example.com", true), sessionId));
        AuthRefreshToken session = new AuthRefreshToken(id, "hash", java.time.Instant.now().plusSeconds(60));
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        when(tokens.parse("token")).thenReturn(new AuthPrincipal(id, "old@example.com", true));
        AppUser user = new AppUser("current@example.com", "hash", false);
        ReflectionTestUtils.setField(user, "id", id);
        when(users.findById(id)).thenReturn(Optional.of(user));
        var request = new MockHttpServletRequest("GET", "/api/admin/organizations");
        request.addHeader("Authorization", "Bearer token");
        var response = new MockHttpServletResponse();
        new SessionAuthenticationFilter(tokens, users, sessions).doFilter(request, response, (req, res) -> {
            AuthPrincipal current = (AuthPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertThat(current.platformAdmin()).isFalse();
            assertThat(current.email()).isEqualTo("current@example.com");
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
}
