package com.aiconnect.llmgateway.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ContentRetentionFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;
    private final RequestContentService content;
    public ContentRetentionFilter(ObjectMapper objectMapper, RequestContentService content) { this.objectMapper = objectMapper; this.content = content; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !"POST".equals(request.getMethod()) || !"/v1/chat/completions".equals(request.getRequestURI()); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        byte[] raw = request.getInputStream().readAllBytes();
        if (isStream(raw)) {
            filterChain.doFilter(new ReplayableBodyRequest(request, raw), response);
            content.capture(response.getHeader("X-Request-Id"), new String(raw, StandardCharsets.UTF_8), null);
            return;
        }
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(new ReplayableBodyRequest(request, raw), wrapped);
            content.capture(wrapped.getHeader("X-Request-Id"), new String(raw, StandardCharsets.UTF_8), new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
        } finally {
            wrapped.copyBodyToResponse();
        }
    }
    private boolean isStream(byte[] raw) { try { JsonNode body = objectMapper.readTree(raw); return body.path("stream").asBoolean(false); } catch (Exception ignored) { return false; } }
}
