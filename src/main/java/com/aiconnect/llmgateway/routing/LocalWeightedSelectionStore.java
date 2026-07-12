package com.aiconnect.llmgateway.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(name = "aiconnect.deployment.shared-state-provider", havingValue = "LOCAL", matchIfMissing = true)
public class LocalWeightedSelectionStore implements WeightedSelectionStore {
    private final ConcurrentHashMap<UUID, AtomicLong> selections = new ConcurrentHashMap<>();

    @Override
    public long count(UUID targetId) {
        return selections.computeIfAbsent(targetId, ignored -> new AtomicLong()).get();
    }

    @Override
    public void increment(UUID targetId) {
        selections.computeIfAbsent(targetId, ignored -> new AtomicLong()).incrementAndGet();
    }
}
