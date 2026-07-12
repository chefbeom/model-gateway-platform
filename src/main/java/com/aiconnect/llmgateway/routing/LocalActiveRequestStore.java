package com.aiconnect.llmgateway.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "aiconnect.deployment.shared-state-provider", havingValue = "LOCAL", matchIfMissing = true)
public class LocalActiveRequestStore implements ActiveRequestStore {
    private final ConcurrentHashMap<UUID, AtomicInteger> active = new ConcurrentHashMap<>();

    @Override
    public int count(UUID deploymentId) {
        return active.computeIfAbsent(deploymentId, ignored -> new AtomicInteger()).get();
    }

    @Override
    public boolean tryAcquire(UUID deploymentId, int maxConcurrency) {
        AtomicInteger counter = active.computeIfAbsent(deploymentId, ignored -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= maxConcurrency) return false;
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }

    @Override
    public void release(UUID deploymentId) {
        active.computeIfPresent(deploymentId, (ignored, counter) ->
                counter.updateAndGet(current -> Math.max(0, current - 1)) == 0 ? null : counter);
    }
}
