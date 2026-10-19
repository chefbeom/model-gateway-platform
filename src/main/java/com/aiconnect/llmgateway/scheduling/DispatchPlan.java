package com.aiconnect.llmgateway.scheduling;

import com.aiconnect.llmgateway.routing.*;
import java.util.*;

/** Owns a reservation until it is transferred to the inference attempt. */
public final class DispatchPlan implements AutoCloseable {
    private final RoutingService routing;
    private final RoutingDecision decision;
    private List<ResolvedTarget> candidates;
    private ResolvedTarget reserved;
    private ActiveRequestRegistry.Lease lease;
    private java.util.function.Supplier<RoutingDecision> validator;

    public DispatchPlan(RoutingService routing, RoutingDecision decision, List<ResolvedTarget> candidates,
                        ResolvedTarget reserved, ActiveRequestRegistry.Lease lease) {
        this.routing = routing; this.decision = decision; this.candidates = List.copyOf(candidates);
        this.reserved = reserved; this.lease = lease;
    }
    public static DispatchPlan immediate(RoutingService routing, RoutingDecision decision) {
        return new DispatchPlan(routing, decision, decision.eligibleTargets(), null, null);
    }
    public RoutingDecision decision() { return decision; }
    public synchronized void revalidateBeforeAttempt(java.util.function.Supplier<RoutingDecision> validator) {this.validator=validator;}
    public List<ResolvedTarget> candidates() { return candidates; }
    public synchronized void restrict(RoutingDecision current) {
        var ids=current.eligibleTargets().stream().map(t->t.deployment().getId()).collect(java.util.stream.Collectors.toSet());
        candidates=candidates.stream().filter(t->ids.contains(t.deployment().getId())).toList();
        if(reserved!=null&&!ids.contains(reserved.deployment().getId())) {
            close(); throw new com.aiconnect.llmgateway.web.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"ROUTING_POLICY_CHANGED","배정 직전 모델 상태 또는 보안 정책이 변경되어 전송하지 않았습니다.");
        }
    }
    public synchronized ActiveRequestRegistry.Lease acquire(ResolvedTarget target) {
        if(validator!=null) {
            var current=validator.get();
            if(current.eligibleTargets().stream().noneMatch(t->t.deployment().getId().equals(target.deployment().getId()))) {
                if(reserved==target)close();
                return null;
            }
        }
        if (target == reserved && lease != null) {
            ActiveRequestRegistry.Lease result = lease; lease = null; reserved = null; return result;
        }
        return routing.acquire(target) ? new ActiveRequestRegistry.Lease(() -> routing.release(target)) : null;
    }
    @Override public synchronized void close() { if (lease != null) { lease.close(); lease = null; } }
}
