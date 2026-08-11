package com.aiconnect.llmgateway.web;

import com.aiconnect.llmgateway.gateway.StreamingChatCompletionGateway;
import com.aiconnect.llmgateway.gateway.StreamingGatewayResult;
import com.aiconnect.llmgateway.quota.SpendQuotaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.io.InputStream;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class StreamingChatFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;
    private final StreamingChatCompletionGateway streamingGateway;
    private final SpendQuotaService spendQuota;

    public StreamingChatFilter(ObjectMapper objectMapper, StreamingChatCompletionGateway streamingGateway,
                               SpendQuotaService spendQuota) {
        this.objectMapper = objectMapper;
        this.streamingGateway = streamingGateway;
        this.spendQuota = spendQuota;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/v1/chat/completions".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        byte[] raw = request.getInputStream().readAllBytes();
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(raw);
        } catch (Exception exception) {
            filterChain.doFilter(new BufferedBodyRequest(request, raw), response);
            return;
        }
        if (!(parsed instanceof ObjectNode object) || !object.path("stream").asBoolean(false)) {
            filterChain.doFilter(new BufferedBodyRequest(request, raw), response);
            return;
        }
        try {
            // SpendQuotaFilter normally performs this check first. The explicit guard is needed because
            // this streaming filter may terminate the chain before later filters are invoked.
            if (request.getAttribute("aiconnect.spendQuotaChecked") == null) {
                spendQuota.check(request.getHeader(HttpHeaders.AUTHORIZATION), object);
                request.setAttribute("aiconnect.spendQuotaChecked", Boolean.TRUE);
            }
            String logicalModel = object.path("model").asText();
            StreamingGatewayResult result = streamingGateway.open(request.getHeader(HttpHeaders.AUTHORIZATION), object);
            response.setStatus(result.statusCode());
            response.setHeader("X-Request-Id", result.requestId());
            if (!result.isSuccessful()) {
                response.setContentType("application/json");
                objectMapper.writeValue(response.getOutputStream(), result.error());
                return;
            }
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            try (InputStream stream = new SseModelRewritingInputStream(result.stream(), objectMapper, logicalModel)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    response.getOutputStream().write(buffer, 0, read);
                    response.flushBuffer();
                }
            }
        } catch (ApiException exception) {
            response.setStatus(exception.getStatus().value());
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(), OpenAiError.of(exception.getMessage(), "invalid_request_error", exception.getCode(), null));
        }
    }
}