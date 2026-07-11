package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.quota.QuotaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class QuotaFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;
    private final QuotaService quotaService;
    public QuotaFilter(ObjectMapper objectMapper, QuotaService quotaService) { this.objectMapper = objectMapper; this.quotaService = quotaService; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !"POST".equals(request.getMethod()) || !"/v1/chat/completions".equals(request.getRequestURI()); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        byte[] raw = request.getInputStream().readAllBytes();
        try {
            JsonNode body = objectMapper.readTree(raw);
            quotaService.check(request.getHeader(HttpHeaders.AUTHORIZATION), body);
            filterChain.doFilter(new BufferedBodyRequest(request, raw), response);
        } catch (ApiException exception) {
            response.setStatus(exception.getStatus().value()); response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(), OpenAiError.of(exception.getMessage(), "invalid_request_error", exception.getCode(), null));
        }
    }
}
