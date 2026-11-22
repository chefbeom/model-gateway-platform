package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.config.GatewayProperties;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
public class AdminTokenFilter extends OncePerRequestFilter {
    private final GatewayProperties properties;
    private final AuditService audit;
    public AdminTokenFilter(GatewayProperties properties, AuditService audit) { this.properties = properties; this.audit = audit; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/admin/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Admin-Token");
        String expected = properties.adminToken();
        boolean platformToken = supplied != null && !supplied.isBlank()
                && expected != null && !expected.isBlank()
                && MessageDigest.isEqual(sha256(supplied), sha256(expected));
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!platformToken && !(principal instanceof AuthPrincipal)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value()); response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"ADMIN_AUTH_REQUIRED\",\"message\":\"A platform token or authenticated administrator is required.\"}");
            return;
        }
        AdminAuditContext auditContext = AdminAuditContext.open();
        try {
            if (platformToken) request.setAttribute("aiconnect.platform-admin", true);
            filterChain.doFilter(request, response);
            if (isMutation(request) && response.getStatus() < 400) {
                Map<String, Object> details = platformToken
                        ? Map.of("method", request.getMethod(), "path", request.getRequestURI(), "status", response.getStatus(), "authMethod", "BREAK_GLASS_TOKEN")
                        : Map.of("method", request.getMethod(), "path", request.getRequestURI(), "status", response.getStatus());
                audit.record(auditContext.organizationId(), platformToken ? null : CurrentActor.userIdOrNull(),
                        "ADMIN_CONFIGURATION_CHANGED", "HTTP_ENDPOINT", null, details);
            }
        } finally {
            AdminAuditContext.clear();
        }
    }
    private boolean isMutation(HttpServletRequest request) { return !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod())); }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not digest token", exception);
        }
    }
}
