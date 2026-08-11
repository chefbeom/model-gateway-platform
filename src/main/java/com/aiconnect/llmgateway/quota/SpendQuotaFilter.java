package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.web.ApiException;
import com.aiconnect.llmgateway.web.BufferedBodyRequest;
import com.aiconnect.llmgateway.web.OpenAiError;
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

/** Separate filter preserves the existing RPM and token quota behavior. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpendQuotaFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final SpendQuotaService quota;

    public SpendQuotaFilter(ObjectMapper mapper, SpendQuotaService quota) {
        this.mapper = mapper;
        this.quota = quota;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/v1/chat/completions".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        byte[] raw = request.getInputStream().readAllBytes();
        SpendQuotaService.Reservation reservation = null;
        try {
            JsonNode body = raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
            reservation = quota.reserve(request.getHeader(HttpHeaders.AUTHORIZATION), body);
            request.setAttribute("aiconnect.spendQuotaChecked", Boolean.TRUE);
            chain.doFilter(new BufferedBodyRequest(request, raw), response);
        } catch (ApiException exception) {
            response.setStatus(exception.getStatus().value());
            response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), OpenAiError.of(exception.getMessage(),
                    "rate_limit_error", exception.getCode(), null));
        } finally {
            if (reservation != null) {
                try {
                    quota.release(reservation);
                } catch (RuntimeException ignored) {
                    // Expiry is the safety backstop if cleanup cannot complete after the response.
                }
            }
        }
    }
}
