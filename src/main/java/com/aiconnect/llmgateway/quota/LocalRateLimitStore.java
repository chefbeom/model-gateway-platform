package com.aiconnect.llmgateway.quota;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "aiconnect.deployment.shared-state-provider", havingValue = "LOCAL", matchIfMissing = true)
public class LocalRateLimitStore implements RateLimitStore {
    private final ConcurrentHashMap<UUID, RateWindow> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(UUID apiKeyId, int limit, Instant now) {
        return windows.computeIfAbsent(apiKeyId, ignored -> new RateWindow()).tryAcquire(limit, now);
    }
}
