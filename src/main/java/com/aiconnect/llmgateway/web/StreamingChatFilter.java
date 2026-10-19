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
import java.util.UUID;
import java.io.InputStream;
import jakarta.servlet.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.aiconnect.llmgateway.scheduling.GatewaySchedulingSupport;

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
            if(streamingGateway.queued(request.getHeader(HttpHeaders.AUTHORIZATION),object)) {
                var future=streamingGateway.openAsync(request.getHeader(HttpHeaders.AUTHORIZATION),object);
                request.setAttribute("aiconnect.executionDone",GatewaySchedulingSupport.lifecycle(future));
                AsyncContext async=request.startAsync(request,response);
                async.setTimeout(900000L);
                AtomicBoolean disconnected=new AtomicBoolean();
                AtomicReference<InputStream> active=new AtomicReference<>();
                async.addListener(new AsyncListener() {
                    public void onComplete(AsyncEvent event) { }
                    public void onStartAsync(AsyncEvent event) { }
                    public void onTimeout(AsyncEvent event) { cancel(); }
                    public void onError(AsyncEvent event) { cancel(); }
                    private void cancel() { disconnected.set(true);future.cancel(false);InputStream stream=active.get();if(stream!=null)try{stream.close();}catch(IOException ignored){} }
                });
                future.whenComplete((result,error)->{
                    if(disconnected.get()) { if(result!=null&&result.stream()!=null)try{result.stream().close();}catch(IOException ignored){} return; }
                    try {
                        streamingGateway.executor().execute(()->{
                            try {
                                if(result!=null)active.set(result.stream());
                                if(disconnected.get())return;
                                if(error!=null) { response.setStatus(503);response.setContentType("application/json");objectMapper.writeValue(response.getOutputStream(),OpenAiError.of("대기 중 요청이 취소되었거나 실패했습니다.","server_error","SCHEDULER_UNAVAILABLE",UUID.randomUUID().toString())); }
                                else {writeResult(response,result,logicalModel); }
                            } catch(IOException|RuntimeException ignored) { /* Client disconnect: the audited stream closes below. */ }
                            finally { InputStream stream=active.getAndSet(null);if(stream!=null)try{stream.close();}catch(IOException ignored){} try{async.complete();}catch(IllegalStateException ignored){} }
                        });
                    } catch(RuntimeException rejected) { if(result!=null&&result.stream()!=null)try{result.stream().close();}catch(IOException ignored){} response.setStatus(503);async.complete(); }
                });
                return;
            }
            StreamingGatewayResult result = streamingGateway.open(request.getHeader(HttpHeaders.AUTHORIZATION), object);
            writeResult(response,result,logicalModel);
        } catch (ApiException exception) {
            response.setStatus(exception.getStatus().value());
            response.setContentType("application/json");
            String requestId = UUID.randomUUID().toString();
            response.setHeader("X-Request-Id", requestId);
            objectMapper.writeValue(response.getOutputStream(), OpenAiError.of(exception.getMessage(), "invalid_request_error", exception.getCode(), requestId));
        }
    }
    private void writeResult(HttpServletResponse response,StreamingGatewayResult result,String logicalModel) throws IOException {
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
    }
}
