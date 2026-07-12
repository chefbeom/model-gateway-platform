package com.aiconnect.llmgateway.quota;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "aiconnect.deployment.shared-state-provider", havingValue = "REDIS")
public class RedisRateLimitStore implements RateLimitStore {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local cutoff = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
            local count = redis.call('ZCARD', key)
            if count >= limit then
              redis.call('PEXPIRE', key, 61000)
              return 0
            end
            redis.call('ZADD', key, now, ARGV[4])
            redis.call('PEXPIRE', key, 61000)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(UUID apiKeyId, int limit, Instant now) {
        long nowMillis = now.toEpochMilli();
        Long result = redis.execute(ACQUIRE, List.of("aiconnect:rate-limit:" + apiKeyId),
                Long.toString(nowMillis), Long.toString(nowMillis - 60_000), Integer.toString(limit),
                nowMillis + ":" + UUID.randomUUID());
        return result != null && result == 1L;
    }
}
