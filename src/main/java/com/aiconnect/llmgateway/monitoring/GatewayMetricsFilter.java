package com.aiconnect.llmgateway.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayMetricsFilter extends OncePerRequestFilter {
    private final MeterRegistry registry;
    public GatewayMetricsFilter(MeterRegistry registry) { this.registry = registry; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/v1/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        long started = System.nanoTime();
        try { filterChain.doFilter(request, response); }
        finally {
            AtomicBoolean recorded=new AtomicBoolean();
            Runnable record=()->{if(!recorded.compareAndSet(false,true))return;
            String endpoint = request.getRequestURI().replaceAll("/[^/]+$", "/{resource}");
            String outcome = response.getStatus() >= 500 ? "server_error" : response.getStatus() >= 400 ? "client_error" : "success";
            registry.counter("llm_gateway_requests_total", "endpoint", endpoint, "outcome", outcome).increment();
            registry.timer("llm_gateway_request_duration", "endpoint", endpoint, "outcome", outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            };
            if(request.isAsyncStarted()) request.getAsyncContext().addListener(new AsyncListener() {
                public void onComplete(AsyncEvent event){record.run();}
                public void onTimeout(AsyncEvent event){} public void onError(AsyncEvent event){} public void onStartAsync(AsyncEvent event){}
            });
            else record.run();
        }
    }
}
