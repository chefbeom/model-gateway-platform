package com.aiconnect.llmgateway.scheduling;

import com.aiconnect.llmgateway.dataprotection.DataProtectionPolicyService;
import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.gateway.RequestCapabilityDetector;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.routing.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class GatewaySchedulingSupport {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GatewaySchedulingSupport.class);
    private final SchedulingPolicyService policies;
    private final RequestScheduler scheduler;
    private final SchedulingTraceService traces;
    private final ApiKeyService keys;
    private final LlmServiceRepository services;
    private final ProjectServiceAccessRepository access;
    private final DataProtectionPolicyService protection;
    private final RoutingService routing;
    private final LlmRequestRepository requests;
    public GatewaySchedulingSupport(SchedulingPolicyService policies,RequestScheduler scheduler,SchedulingTraceService traces,
            ApiKeyService keys,LlmServiceRepository services,ProjectServiceAccessRepository access,
            DataProtectionPolicyService protection,RoutingService routing,LlmRequestRepository requests) {
        this.policies=policies;this.scheduler=scheduler;this.traces=traces;this.keys=keys;this.services=services;
        this.access=access;this.protection=protection;this.routing=routing;this.requests=requests;
    }
    public boolean enabled(String authorization,ObjectNode body) {
        var credentials=keys.authenticate(authorization);
        return policies.enabled(credentials.project().getOrganizationId(),body.path("model").asText());
    }
    public boolean enabled(LlmService service) { return policies.settings(service.getId()).mode()!=SchedulingMode.IMMEDIATE; }
    public java.util.concurrent.Executor executor() { return scheduler.executor(); }
    public <T> CompletableFuture<T> schedule(LlmRequest audit,String authorization,ObjectNode body,
            Function<DispatchPlan,T> execute,Function<ApiException,T> failure) {
        TrackedFuture<T> result=new TrackedFuture<>();
        Function<Boolean,RoutingDecision> reevaluate=record->{
            var credentials=keys.authenticate(authorization);
            var service=services.findById(audit.getServiceId()).filter(s->s.isEnabled()&&!s.isDeleted())
                    .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"MODEL_NOT_FOUND","대기 중 논리 서비스가 비활성화되었습니다."));
            if(!access.existsByIdProjectIdAndIdServiceId(credentials.project().getId(),service.getId()))
                throw new ApiException(HttpStatus.FORBIDDEN,"MODEL_NOT_ALLOWED","대기 중 모델 접근 권한이 변경되었습니다.");
            var policy=protection.inspect(credentials.project(),credentials.apiKey(),service,body,null);
            if(record) {audit.recordDataProtection(policy.policy().mode().name(),policy.policy().level().name(),policy.action().name(),policy.classificationSummary(),policy.externalAllowed());requests.save(audit);}
            if(policy.blocked()) throw new ApiException(HttpStatus.FORBIDDEN,"DATA_POLICY_BLOCKED","현재 데이터 보호 정책이 요청 전송을 차단했습니다.");
            return routing.evaluateForScheduling(service,RequestCapabilityDetector.detect(body),credentials.project().getId(),policy.routingConstraint());
        };
        Supplier<RoutingDecision> evaluate=()->reevaluate.apply(false);
        CompletableFuture<DispatchPlan> admission;
        try {admission=scheduler.admit(audit.getRequestId(),audit.getServiceId(),audit.getApiKeyId(),
                body.toString().getBytes(StandardCharsets.UTF_8).length,policies.settings(audit.getServiceId()),evaluate);}
        catch(RuntimeException error) {completeFailure(audit,result,failure,error);return result;}
        result.whenComplete((value,error)->{ if(result.isCancelled()) admission.cancel(false); });
        admission.whenComplete((plan,error)->{
            if(error!=null) {
                ApiException api=error instanceof ApiException e?e:error instanceof CancellationException?new ApiException(HttpStatus.REQUEST_TIMEOUT,"REQUEST_CANCELLED","클라이언트가 대기 요청을 취소했습니다."):new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SCHEDULER_UNAVAILABLE","대기열 요청을 처리할 수 없습니다.");
                completeFailure(audit,result,failure,api);
                return;
            }
            try {
                scheduler.executor().execute(()->{
                    try(plan) {
                        if(result.isCancelled()) {audit.fail("REQUEST_CANCELLED",408,Duration.between(audit.getStartedAt(),Instant.now()).toMillis(),0);requests.save(audit);traces.finish(audit.getRequestId(),"CANCELLED","REQUEST_CANCELLED"); result.finished.complete(null); return; }
                        // Recheck after worker handoff; no request payload or credentials were persisted in Redis.
                        plan.restrict(evaluate.get());
                        plan.revalidateBeforeAttempt(()->reevaluate.apply(true));
                        T value=execute.apply(plan);
                        if(value instanceof com.aiconnect.llmgateway.gateway.StreamingGatewayResult stream && stream.stream()!=null) {
                            var wrapped=new java.io.FilterInputStream(stream.stream()) {
                                @Override public int read() throws java.io.IOException { int n=super.read();if(n<0)result.finished.complete(null);return n; }
                                @Override public int read(byte[] b,int off,int len) throws java.io.IOException { int n=in.read(b,off,len);if(n<0)result.finished.complete(null);return n; }
                                @Override public void close() throws java.io.IOException { try {super.close();}finally {result.finished.complete(null);} }
                            };
                            @SuppressWarnings("unchecked") T tracked=(T)new com.aiconnect.llmgateway.gateway.StreamingGatewayResult(stream.statusCode(),stream.requestId(),wrapped,stream.error());
                            value=tracked;
                        } else result.finished.complete(null);
                        if(!audit.isStream()) traces.finish(audit.getRequestId(),audit.getStatus().name(),audit.getErrorCode());
                        else if(audit.getStatus()!=RequestStatus.IN_PROGRESS) traces.finish(audit.getRequestId(),audit.getStatus().name(),audit.getErrorCode());
                        if(!result.complete(value) && value instanceof com.aiconnect.llmgateway.gateway.StreamingGatewayResult stream && stream.stream()!=null) {
                            try { stream.stream().close(); } catch(java.io.IOException ignored) { }
                        }
                    } catch(Exception exception) { completeFailure(audit,result,failure,exception); }
                });
            } catch(RejectedExecutionException exception) { plan.close(); completeFailure(audit,result,failure,new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SCHEDULER_BUSY","현재 API 서버의 실행 슬롯이 가득 찼습니다. 잠시 후 다시 요청하세요.")); }
        });
        return result;
    }
    private <T> void completeFailure(LlmRequest audit,CompletableFuture<T> result,Function<ApiException,T> failure,Exception exception) {
        ApiException api=exception instanceof ApiException e?e:new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SCHEDULED_REQUEST_FAILED","배정된 요청을 처리하지 못했습니다. 중복 요청은 자동 실행하지 않았습니다.");
        try {audit.fail(api.getCode(),api.getStatus().value(),Duration.between(audit.getStartedAt(),Instant.now()).toMillis(),0);requests.save(audit);}
        catch(RuntimeException error){log.warn("Scheduled request failure metadata could not be saved for requestId={}",audit.getRequestId(),error);}
        traces.finish(audit.getRequestId(),"REQUEST_CANCELLED".equals(api.getCode())?"CANCELLED":"QUEUE_WAIT_TIMEOUT".equals(api.getCode())?"EXPIRED":"FAILED",api.getCode());
        try {result.complete(failure.apply(api));} catch(RuntimeException error){result.completeExceptionally(error);}
        finally {if(result instanceof TrackedFuture<?> tracked)tracked.finished.complete(null);}
    }
    public void finishStream(LlmRequest audit) { traces.finish(audit.getRequestId(),audit.getStatus().name(),audit.getErrorCode()); }
    public static CompletableFuture<Void> lifecycle(CompletableFuture<?> future) { return future instanceof TrackedFuture<?> tracked?tracked.finished:CompletableFuture.completedFuture(null); }
    private static final class TrackedFuture<T> extends CompletableFuture<T> { final CompletableFuture<Void> finished=new CompletableFuture<>(); }
}
