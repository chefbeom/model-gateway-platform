package com.aiconnect.llmgateway.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "aiconnect.deployment.shared-state-provider", havingValue = "REDIS")
public class RedisWeightedSelectionStore implements WeightedSelectionStore {
    private final StringRedisTemplate redis;

    public RedisWeightedSelectionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long count(UUID targetId) {
        String value = redis.opsForValue().get(key(targetId));
        if (value == null) return 0;
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    @Override
    public void increment(UUID targetId) {
        String key = key(targetId);
        redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofDays(7));
    }

    private String key(UUID targetId) { return "aiconnect:weighted-selections:" + targetId; }
}
