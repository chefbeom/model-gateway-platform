package com.aiconnect.llmgateway.retention;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(ContentRetentionFilter.class);

    private final ObjectMapper objectMapper;
    private final RequestContentService content;

    public ContentRetentionFilter(ObjectMapper objectMapper, RequestContentService content) {
        this.objectMapper = objectMapper;
        this.content = content;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/v1/chat/completions".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        byte[] raw = request.getInputStream().readAllBytes();
        String requestBody = new String(raw, StandardCharsets.UTF_8);
        if (isStream(raw)) {
            filterChain.doFilter(new ReplayableBodyRequest(request, raw), response);
            captureSafely(response.getHeader("X-Request-Id"), requestBody, null);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(new ReplayableBodyRequest(request, raw), wrapped);
            captureSafely(wrapped.getHeader("X-Request-Id"), requestBody,
                    new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
        } finally {
            wrapped.copyBodyToResponse();
        }
    }

    /** Retention must never change the success or failure outcome of inference. */
    private void captureSafely(String requestId, String request, String response) {
        try {
            content.capture(requestId, request, response);
        } catch (RuntimeException exception) {
            log.warn("Request content was not retained for requestId={}; preserving API response.", requestId, exception);
        }
    }

    private boolean isStream(byte[] raw) {
        try {
            JsonNode body = objectMapper.readTree(raw);
            return body.path("stream").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }
}
