package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.web.ApiException;
import com.aiconnect.llmgateway.web.BufferedBodyRequest;
import com.aiconnect.llmgateway.web.OpenAiError;
import com.aiconnect.llmgateway.service.ApiKeyService;
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
import java.util.UUID;
import jakarta.servlet.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate filter preserves the existing RPM and token quota behavior. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SpendQuotaFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final SpendQuotaService quota;
    private final ApiKeyService apiKeys;
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;

    public SpendQuotaFilter(ObjectMapper mapper, SpendQuotaService quota, ApiKeyService apiKeys) {
        this.mapper = mapper;
        this.quota = quota;
        this.apiKeys = apiKeys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/v1/chat/completions".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        SpendQuotaService.Reservation reservation = null;
        try {
            var credentials = apiKeys.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (request.getContentLengthLong() > MAX_BODY_BYTES) throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", "Request body exceeds the permitted size.");
            byte[] raw = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (raw.length > MAX_BODY_BYTES) throw new ApiException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", "Request body exceeds the permitted size.");
            JsonNode body = raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
            reservation = quota.reserve(credentials, body);
            request.setAttribute("aiconnect.spendQuotaChecked", Boolean.TRUE);
            chain.doFilter(new BufferedBodyRequest(request, raw), response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            response.setStatus(400); response.setContentType("application/json");
            mapper.writeValue(response.getOutputStream(), OpenAiError.of("Invalid JSON request body.", "invalid_request_error", "INVALID_JSON", UUID.randomUUID().toString()));
        } catch (ApiException exception) {
            response.setStatus(exception.getStatus().value());
            response.setContentType("application/json");
            String requestId = UUID.randomUUID().toString();
            response.setHeader("X-Request-Id", requestId);
            mapper.writeValue(response.getOutputStream(), OpenAiError.of(exception.getMessage(),
                    "rate_limit_error", exception.getCode(), requestId));
        } finally {
            if (reservation != null) {
                SpendQuotaService.Reservation held=reservation;
                AtomicBoolean released=new AtomicBoolean();
                Runnable release=()->{if(released.compareAndSet(false,true))try{quota.release(held);}catch(RuntimeException ignored){} };
                Object lifecycle=request.getAttribute("aiconnect.executionDone");
                if(lifecycle instanceof CompletableFuture<?> done) done.whenComplete((value,error)->release.run());
                else release.run();
            }
        }
    }
}
