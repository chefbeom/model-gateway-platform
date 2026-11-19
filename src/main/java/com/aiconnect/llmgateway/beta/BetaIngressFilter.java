package com.aiconnect.llmgateway.beta;

import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Trusts the ingress marker only when inserted by a private, isolated proxy. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BetaIngressFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-AIConnect-Beta-Ingress";
    private final String marker;
    private final ApiKeyService keys;
    private final BetaAccessService beta;
    private final ObjectMapper mapper;
    public BetaIngressFilter(@Value("${AICONNECT_BETA_INGRESS_TOKEN:}") String marker, ApiKeyService keys, BetaAccessService beta, ObjectMapper mapper) {
        this.marker=marker; this.keys=keys; this.beta=beta; this.mapper=mapper;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String supplied = request.getHeader(HEADER);
        if (supplied == null) { chain.doFilter(request, response); return; }
        if (marker == null || marker.length() < 32 || !MessageDigest.isEqual(marker.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            fail(response, 403, "BETA_INGRESS_DENIED"); return;
        }
        String method=request.getMethod(), path=request.getRequestURI();
        boolean redeem="POST".equals(method) && "/api/beta/invitations/redeem".equals(path);
        boolean api=("POST".equals(method) && "/v1/chat/completions".equals(path)) || ("GET".equals(method) && "/v1/models".equals(path));
        if (!redeem && !api) { fail(response, 404, "NOT_FOUND"); return; }
        if (api) {
            try {
                var project = keys.authenticate(request.getHeader(HttpHeaders.AUTHORIZATION)).project().getId();
                beta.requirePublicBetaProject(project); beta.checkActiveProject(project);
            }
            catch (ApiException exception) { fail(response, exception.getStatus().value(), exception.getCode()); return; }
        }
        request.setAttribute("aiconnect.beta.ingress", Boolean.TRUE);
        chain.doFilter(request,response);
    }
    private void fail(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), Map.of("error", Map.of("code",code,"type","invalid_request_error","message","Request is not permitted.")));
    }
}
