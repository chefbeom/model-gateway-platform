package com.aiconnect.llmgateway.scheduling;

import com.aiconnect.llmgateway.routing.*;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Payloads stay in the owner's memory. Redis contains bounded routing metadata only. */
@Component
public class RequestScheduler {
    private final ActiveRequestRegistry registry;
    private final RoutingService routing;
    private final SchedulingTraceService traces;
    private final ConcurrentMap<String,Pending> pending=new ConcurrentHashMap<>();
    private final ScheduledExecutorService coordinator=Executors.newSingleThreadScheduledExecutor(r->daemon(r,"request-scheduler"));
    private final ExecutorService workers=new ThreadPoolExecutor(4,64,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->daemon(r,"scheduled-inference"),new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean signalled=new AtomicBoolean();

    public RequestScheduler(ActiveRequestRegistry registry,RoutingService routing,SchedulingTraceService traces) {
        this.registry=registry; this.routing=routing; this.traces=traces;
        registry.onCapacityAvailable(this::signal);
        coordinator.scheduleWithFixedDelay(this::drain,100,500,TimeUnit.MILLISECONDS);
    }
    private static Thread daemon(Runnable r,String name) { Thread t=new Thread(r,name); t.setDaemon(true); return t; }
    public Executor executor() { return workers; }
    public CompletableFuture<DispatchPlan> admit(String id,UUID service,UUID key,long bytes,SchedulingSettings settings,Supplier<RoutingDecision> evaluate) {
        settings.validate();
        long now=System.currentTimeMillis();
        Pending p=new Pending(id,service,key,bytes,now,now+settings.maxWaitMs(),settings,evaluate);
        traces.queued(id,service,settings.mode(),now,p.deadline);
        p.future.whenComplete((plan,error)->{
            if(p.future.isCancelled()) { synchronized(p) { pending.remove(id,p); registry.removeTicket(id); if(plan!=null)plan.close(); traces.finish(id,"CANCELLED","REQUEST_CANCELLED"); } }
        });
        synchronized(p) {
            try {
                if(tryDispatch(p,now,false)) return p.future;
                QueueTicket ticket=ticket(p,resources(p.evaluate.get(),p,now));
                String rejected=registry.enqueue(ticket,settings);
                if(rejected!=null) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,rejected,"대기열 한도를 초과했습니다. 잠시 후 다시 요청하세요.");
                pending.put(id,p);
            } catch(Exception e) { fail(p,e); }
        }
        signal(); return p.future;
    }
    private List<ResolvedTarget> allowed(RoutingDecision decision,Pending p,long now) {
        List<ResolvedTarget> local=decision.eligibleTargets().stream().filter(t->!t.external()).toList();
        if(p.settings.mode()==SchedulingMode.LOCAL_ONLY_QUEUE) return local;
        if(now-p.created<p.settings.localWaitMs() && !local.isEmpty()) return local;
        return decision.eligibleTargets();
    }
    private List<String> resources(RoutingDecision decision,Pending p,long now) {
        return allowed(decision,p,now).stream().flatMap(t->t.endpoint()==null?java.util.stream.Stream.of("d:"+t.deployment().getId()):java.util.stream.Stream.of("d:"+t.deployment().getId(),"r:"+t.endpoint().getId())).distinct().toList();
    }
    private QueueTicket ticket(Pending p,List<String> resources) { return new QueueTicket(p.id,p.service,p.key,p.bytes,p.created,p.deadline,resources); }
    private boolean tryDispatch(Pending p,long now,boolean queued) {
        if(p.future.isDone()) return true;
        RoutingDecision decision=p.evaluate.get();
        List<ResolvedTarget> allowed=allowed(decision,p,now);
        if(allowed.isEmpty()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"LOCAL_MODEL_UNAVAILABLE","호환되는 사용 가능 모델이 없습니다. 오프라인·미로드·기능 미지원 모델은 대기열에 넣지 않습니다.");
        if(queued) registry.updateTicket(ticket(p,resources(decision,p,now)));
        for(ResolvedTarget candidate:allowed) {
            ActiveRequestRegistry.Lease lease=routing.reserve(candidate,queued?p.id:null);
            if(lease==null) continue;
            List<ResolvedTarget> ordered=new ArrayList<>(); ordered.add(candidate);
            (p.settings.mode()==SchedulingMode.LOCAL_ONLY_QUEUE?allowed:decision.eligibleTargets()).stream().filter(t->t!=candidate).forEach(ordered::add);
            DispatchPlan plan=new DispatchPlan(routing,decision,ordered,candidate,lease);
            try {
                traces.dispatched(p.id,candidate.deployment().getId(),candidate.external()?"LOCAL_WAIT_EXPIRED_EXTERNAL":queued?"QUEUED_LOCAL_AVAILABLE":"LOCAL_AVAILABLE",now);
                pending.remove(p.id,p);
                if(!p.future.complete(plan)) plan.close();
                return true;
            } catch(RuntimeException e) { plan.close(); throw e; }
        }
        return false;
    }
    public void signal() {
        if(!coordinator.isShutdown() && signalled.compareAndSet(false,true)) coordinator.execute(()->{signalled.set(false);drain();});
    }
    private void drain() {
        pending.values().stream().sorted(Comparator.comparingLong(p->p.created)).forEach(p->{ synchronized(p) {
            try {
                long now=System.currentTimeMillis();
                if(now>=p.deadline) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"QUEUE_WAIT_TIMEOUT","로컬 모델 대기 시간을 초과했습니다. 실행 중인 요청은 이동하거나 중복 실행하지 않습니다.");
                tryDispatch(p,now,true);
            } catch(Exception error) { fail(p,error); }
        }});
    }
    private void fail(Pending p,Exception error) {
        pending.remove(p.id,p);
        try { registry.removeTicket(p.id); } catch(RuntimeException ignored) { /* Redis unavailable: fail closed; metadata expires. */ }
        String code=error instanceof ApiException api?api.getCode():"SCHEDULER_UNAVAILABLE";
        try {traces.finish(p.id,"QUEUE_WAIT_TIMEOUT".equals(code)?"EXPIRED":"FAILED",code);}
        finally {p.future.completeExceptionally(error instanceof ApiException?error:new ApiException(HttpStatus.SERVICE_UNAVAILABLE,code,"공유 대기열을 확인할 수 없어 요청을 전송하지 않았습니다."));}
    }
    @PreDestroy public void close() {
        coordinator.shutdownNow();
        pending.values().forEach(p->p.future.cancel(false)); workers.shutdown();
    }
    private static final class Pending {
        final String id; final UUID service,key; final long bytes,created,deadline; final SchedulingSettings settings;
        final Supplier<RoutingDecision> evaluate; final CompletableFuture<DispatchPlan> future=new CompletableFuture<>();
        Pending(String id,UUID service,UUID key,long bytes,long created,long deadline,SchedulingSettings settings,Supplier<RoutingDecision> evaluate) { this.id=id;this.service=service;this.key=key;this.bytes=bytes;this.created=created;this.deadline=deadline;this.settings=settings;this.evaluate=evaluate; }
    }
}
