package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.identity.AccessTokenService;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
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
    public SessionAuthenticationFilter(AccessTokenService tokens) { this.tokens = tokens; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/") || request.getRequestURI().startsWith("/api/auth/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ") && !header.substring(7).startsWith("sk_llmg_")) {
            try {
                AuthPrincipal principal = tokens.parse(header.substring(7));
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
