package com.aiconnect.llmgateway.scheduling;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.routing.*;
import com.aiconnect.llmgateway.web.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestSchedulerTest {
    private final ActiveRequestRegistry registry=new ActiveRequestRegistry();
    private final RoutingService routing=mock(RoutingService.class);
    private final SchedulingTraceService traces=mock(SchedulingTraceService.class);
    private final RequestScheduler scheduler=new RequestScheduler(registry,routing,traces);
    private final UUID service=UUID.randomUUID();
    @BeforeEach void reserve() {
        when(routing.reserve(any(),nullable(String.class))).thenAnswer(call->{
            ResolvedTarget t=call.getArgument(0);String ticket=call.getArgument(1);
            UUID runtime=t.external()?null:t.endpoint().getId();
            if(!registry.tryAcquire(t.deployment().getId(),t.maxConcurrency(),runtime,t.external()?0:t.endpoint().getMaxConcurrentRequests(),ticket))return null;
            return new ActiveRequestRegistry.Lease(()->registry.release(t.deployment().getId(),runtime));
        });
    }
    @AfterEach void stop() {scheduler.close();}
    private SchedulingSettings settings(SchedulingMode mode,long local,long max) {return new SchedulingSettings(mode,local,max,100,1024*1024,100);}
    private ResolvedTarget target(boolean external) {
        RuntimeEndpoint endpoint=new RuntimeEndpoint(UUID.randomUUID(),RuntimeType.LM_STUDIO,"http://localhost:1234",null);
        ReflectionTestUtils.setField(endpoint,"id",UUID.randomUUID());endpoint.recordHealth(true);
        ModelDeployment model=new ModelDeployment(endpoint.getId(),"test","test",null,null,8192,true,1,"[]");
        ReflectionTestUtils.setField(model,"id",UUID.randomUUID());
        ExternalProvider provider=external?new ExternalProvider(UUID.randomUUID(),ExternalProviderType.OPENAI,"external","https://example.test/v1","encrypted"):null;
        return new ResolvedTarget(null,model,external?null:endpoint,provider,1,external?"AUTO_FAILOVER":"LOCAL");
    }
    private RoutingDecision decision(ResolvedTarget... targets) {return new RoutingDecision(List.of(targets),Set.of(),false,"COMPATIBLE","SAFE",List.of());}
    private CompletableFuture<DispatchPlan> admit(String id,SchedulingSettings settings,java.util.function.Supplier<RoutingDecision> evaluate) {return scheduler.admit(id,service,UUID.randomUUID(),100,settings,evaluate);}
    @Test void nineteenRequestsUseThreeLocalSlotsAndReuseEachCompletedSlotExactlyOnce() throws Exception {
        var a=target(false);var b=target(false);var c=target(false);var cloud=target(true);
        var futures=new ArrayList<CompletableFuture<DispatchPlan>>();
        for(int i=0;i<19;i++)futures.add(admit("request-"+i,settings(SchedulingMode.LOCAL_ONLY_QUEUE,0,10000),()->decision(a,b,c,cloud)));
        assertThat(futures.stream().filter(CompletableFuture::isDone).count()).isEqualTo(3);
        assertThat(registry.snapshot(service).queuedRequests()).isEqualTo(16);
        ArrayDeque<DispatchPlan> running=new ArrayDeque<>();
        for(int i=0;i<3;i++)running.add(futures.get(i).get(1,TimeUnit.SECONDS));
        for(int i=3;i<19;i++) {
            running.remove().close();
            var next=futures.get(i).get(3,TimeUnit.SECONDS);assertThat(next.candidates()).noneMatch(ResolvedTarget::external);running.add(next);
            assertThat(registry.runtimeCount(a.endpoint().getId())+registry.runtimeCount(b.endpoint().getId())+registry.runtimeCount(c.endpoint().getId())).isEqualTo(3);
        }
        running.forEach(DispatchPlan::close);
        assertThat(registry.snapshot(service).queuedRequests()).isZero();
        assertThat(registry.count(cloud.deployment().getId())).isZero();
        verify(traces,times(19)).dispatched(anyString(),any(),anyString(),anyLong());
    }
    @Test void localFirstWaitSpillsOnlyUnstartedRequestToExternalAfterBudget() throws Exception {
        var local=target(false);var cloud=target(true);
        var held=admit("held",settings(SchedulingMode.LOCAL_ONLY_QUEUE,0,5000),()->decision(local)).get();
        var waiting=admit("waiting",settings(SchedulingMode.LOCAL_FIRST_WAIT,150,3000),()->decision(local,cloud));
        assertThat(waiting.isDone()).isFalse();
        var dispatched=waiting.get(3,TimeUnit.SECONDS);
        assertThat(dispatched.candidates().get(0).external()).isTrue();
        assertThat(registry.count(local.deployment().getId())).isEqualTo(1);
        verify(traces).dispatched(eq("waiting"),eq(cloud.deployment().getId()),eq("LOCAL_WAIT_EXPIRED_EXTERNAL"),anyLong());
        dispatched.close();held.close();
    }
    @Test void cancellationRemovesPendingMetadataAndDoesNotLeakCapacity() throws Exception {
        var local=target(false);var held=admit("held",settings(SchedulingMode.LOCAL_ONLY_QUEUE,0,5000),()->decision(local)).get();
        var waiting=admit("cancel",settings(SchedulingMode.LOCAL_ONLY_QUEUE,0,5000),()->decision(local));
        assertThat(waiting.cancel(false)).isTrue();
        assertThat(registry.snapshot(service).queuedRequests()).isZero();
        held.close();assertThat(registry.runtimeCount(local.endpoint().getId())).isZero();
    }
    @Test void timeoutNeverSendsLocalOnlyRequestToCloud() throws Exception {
        var local=target(false);var held=admit("held",settings(SchedulingMode.LOCAL_ONLY_QUEUE,0,5000),()->decision(local)).get();
        var cloud=target(true);var waiting=admit("timeout",settings(SchedulingMode.LOCAL_ONLY_QUEUE,0,100),()->decision(local,cloud));
        assertThatThrownBy(()->waiting.get(3,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class)
                .satisfies(e->assertThat(((ApiException)e.getCause()).getCode()).isEqualTo("QUEUE_WAIT_TIMEOUT"));
        assertThat(registry.count(cloud.deployment().getId())).isZero();held.close();
    }
    @Test void queuedAuthorizationIsRecheckedAndRequestIsNotSentAfterRevocation() throws Exception {
        var local=target(false);var held=admit("held",settings(SchedulingMode.LOCAL_ONLY_QUEUE,0,5000),()->decision(local)).get();
        AtomicBoolean permitted=new AtomicBoolean(true);
        var waiting=admit("revoked",settings(SchedulingMode.LOCAL_ONLY_QUEUE,0,5000),()->{if(!permitted.get())throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"MODEL_NOT_ALLOWED","revoked");return decision(local);});
        permitted.set(false);held.close();
        assertThatThrownBy(()->waiting.get(3,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        assertThat(registry.count(local.deployment().getId())).isZero();
    }
    @Test void permanentlyIneligibleTargetsFailWithoutQueueing() {
        var result=admit("missing",settings(SchedulingMode.LOCAL_ONLY_QUEUE,0,5000),()->decision(target(true)));
        assertThat(result).isCompletedExceptionally();assertThat(registry.snapshot(service).queuedRequests()).isZero();
    }
}
