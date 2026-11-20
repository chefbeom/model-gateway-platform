package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.identity.AccessTokenService;
import com.aiconnect.llmgateway.identity.AccountTier;
import com.aiconnect.llmgateway.identity.AppUser;
import com.aiconnect.llmgateway.identity.AppUserRepository;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.AuthRefreshTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokenService tokens;
    private final AppUserRepository users;
    private final AuthRefreshTokenRepository sessions;

    public SessionAuthenticationFilter(AccessTokenService tokens, AppUserRepository users, AuthRefreshTokenRepository sessions) {
        this.tokens = tokens;
        this.users = users;
        this.sessions = sessions;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String uri = request.getRequestURI();
        boolean isAuthPath = uri.startsWith("/api/auth/");
        boolean isTotpAuthPath = uri.startsWith("/api/auth/totp/");

        if (header != null && header.startsWith("Bearer ") && !header.substring(7).startsWith("sk_llmg_")) {
            try {
                var parsed = tokens.parseSession(header.substring(7));
                AuthPrincipal parsedPrincipal = parsed.principal();
                var userId = parsedPrincipal.userId();
                var session = sessions.findById(parsed.sessionId())
                        .filter(item -> item.isUsable(Instant.now()) && item.getUserId().equals(userId))
                        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "The session is unavailable."));
                var user = users.findById(userId)
                        .filter(AppUser::isEnabled)
                        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "The account is unavailable."));

                if (!isAuthPath) {
                    if (!user.isTotpEnabled()) {
                        writeError(response, HttpStatus.FORBIDDEN, "TOTP_ENROLLMENT_REQUIRED",
                                "Two-factor authentication enrollment is required before accessing this resource.");
                        return;
                    }
                    if (!session.isMfaVerified()) {
                        writeError(response, HttpStatus.FORBIDDEN, "TOTP_VERIFICATION_REQUIRED",
                                "Two-factor authentication verification is required for this session.");
                        return;
                    }
                }

                AuthPrincipal principal = new AuthPrincipal(
                        user.getId(),
                        user.getEmail(),
                        user.isPlatformAdmin() && parsedPrincipal.platformAdmin(),
                        AccountTier.effectiveFor(user),
                        user.isTotpEnabled(),
                        session.isMfaVerified()
                );
                request.setAttribute("aiconnect.session-id", session.getId());
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority(principal.platformAdmin() ? "ROLE_PLATFORM_ADMIN" : "ROLE_USER"))));
            } catch (ApiException exception) {
                if (!isAuthPath || isTotpAuthPath) {
                    writeError(response, HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN",
                            "The access token is invalid or expired.");
                    return;
                }
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
