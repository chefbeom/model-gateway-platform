package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.identity.AccessTokenService;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.AppUserRepository;
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
import java.util.List;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokenService tokens;
    private final AppUserRepository users;
    private final AuthRefreshTokenRepository sessions;
    public SessionAuthenticationFilter(AccessTokenService tokens, AppUserRepository users, AuthRefreshTokenRepository sessions) { this.tokens = tokens; this.users = users; this.sessions = sessions; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/") || request.getRequestURI().startsWith("/api/auth/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ") && !header.substring(7).startsWith("sk_llmg_")) {
            try {
                var parsed = tokens.parseSession(header.substring(7));
                AuthPrincipal principal = parsed.principal();
                var userId = principal.userId();
                sessions.findById(parsed.sessionId())
                        .filter(item -> item.isUsable(java.time.Instant.now()) && item.getUserId().equals(userId))
                        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "The session is unavailable."));
                var user = users.findById(principal.userId()).filter(item -> item.isEnabled())
                        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "The account is unavailable."));
                principal = new AuthPrincipal(user.getId(), user.getEmail(), user.isPlatformAdmin() && principal.platformAdmin());
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority(principal.platformAdmin() ? "ROLE_PLATFORM_ADMIN" : "ROLE_USER"))));
            } catch (ApiException exception) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value()); response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"INVALID_ACCESS_TOKEN\",\"message\":\"The access token is invalid or expired.\"}");
                return;
            }
        }
        try { filterChain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }
}
