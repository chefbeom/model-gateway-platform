package com.aiconnect.llmgateway.routing;

import com.aiconnect.llmgateway.cluster.DeploymentProfileProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "aiconnect.deployment.shared-state-provider", havingValue = "REDIS")
public class RedisActiveRequestStore implements ActiveRequestStore {
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local total = 0
            for _, counterKey in ipairs(redis.call('SMEMBERS', KEYS[1])) do
              local value = redis.call('GET', counterKey)
              if value then total = total + tonumber(value)
              else redis.call('SREM', KEYS[1], counterKey) end
            end
            return total
            """, Long.class);
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local total = 0
            for _, counterKey in ipairs(redis.call('SMEMBERS', KEYS[1])) do
              local value = redis.call('GET', counterKey)
              if value then total = total + tonumber(value)
              else redis.call('SREM', KEYS[1], counterKey) end
            end
            if total >= tonumber(ARGV[1]) then return 0 end
            local current = redis.call('INCR', KEYS[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            redis.call('SADD', KEYS[1], KEYS[2])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) * 2)
            return current
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[2]) or '0')
            if current <= 1 then
              redis.call('DEL', KEYS[2])
              redis.call('SREM', KEYS[1], KEYS[2])
              return 0
            end
            current = redis.call('DECR', KEYS[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[1])
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String instanceId;
    private final Duration leaseTtl;
    private final ConcurrentHashMap<UUID, AtomicInteger> localActive = new ConcurrentHashMap<>();

    public RedisActiveRequestStore(StringRedisTemplate redis, DeploymentProfileProperties properties) {
        this.redis = redis;
        this.instanceId = properties.getInstanceId();
        this.leaseTtl = properties.getActiveRequestCounterTtl();
    }

    @Override
    public int count(UUID deploymentId) {
        Long result = redis.execute(COUNT, List.of(indexKey(deploymentId)));
        return result == null ? 0 : Math.toIntExact(result);
    }

    @Override
    public boolean tryAcquire(UUID deploymentId, int maxConcurrency) {
        Long result = redis.execute(ACQUIRE, List.of(indexKey(deploymentId), instanceKey(deploymentId)),
                Integer.toString(maxConcurrency), Long.toString(leaseTtl.toMillis()));
        if (result == null || result <= 0) return false;
        localActive.computeIfAbsent(deploymentId, ignored -> new AtomicInteger()).incrementAndGet();
        return true;
    }

    @Override
    public void release(UUID deploymentId) {
        redis.execute(RELEASE, List.of(indexKey(deploymentId), instanceKey(deploymentId)),
                Long.toString(leaseTtl.toMillis()));
        localActive.computeIfPresent(deploymentId, (ignored, counter) ->
                counter.updateAndGet(value -> Math.max(0, value - 1)) == 0 ? null : counter);
    }

    @Scheduled(fixedDelayString = "${aiconnect.deployment.active-request-heartbeat-ms:30000}")
    public void refreshActiveLeases() {
        localActive.forEach((deploymentId, counter) -> {
            if (counter.get() > 0) {
                redis.expire(instanceKey(deploymentId), leaseTtl);
                redis.opsForSet().add(indexKey(deploymentId), instanceKey(deploymentId));
                redis.expire(indexKey(deploymentId), leaseTtl.multipliedBy(2));
            }
        });
    }

    private String indexKey(UUID deploymentId) {
        return "aiconnect:active-requests:index:" + deploymentId;
    }

    private String instanceKey(UUID deploymentId) {
        return "aiconnect:active-requests:" + deploymentId + ":" + instanceId;
    }
}
