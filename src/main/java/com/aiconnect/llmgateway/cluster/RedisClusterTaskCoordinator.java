package com.aiconnect.llmgateway.cluster;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "aiconnect.deployment.shared-state-provider", havingValue = "REDIS")
public class RedisClusterTaskCoordinator implements ClusterTaskCoordinator {
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final DeploymentProfileProperties properties;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "aiconnect-redis-lock-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public RedisClusterTaskCoordinator(StringRedisTemplate redis, DeploymentProfileProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public boolean runIfLeader(String taskName, Runnable task) {
        String key = "aiconnect:task-lock:" + taskName;
        String token = properties.getInstanceId() + ":" + UUID.randomUUID();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, properties.getLockTtl());
        if (!Boolean.TRUE.equals(acquired)) return false;

        long ttlMillis = properties.getLockTtl().toMillis();
        long renewEveryMillis = Math.max(1_000, ttlMillis / 3);
        ScheduledFuture<?> renewal = watchdog.scheduleAtFixedRate(
                () -> renew(key, token, ttlMillis), renewEveryMillis, renewEveryMillis, TimeUnit.MILLISECONDS);
        try {
            task.run();
            return true;
        } finally {
            renewal.cancel(false);
            redis.execute(RELEASE, List.of(key), token);
        }
    }

    private void renew(String key, String token, long ttlMillis) {
        try {
            redis.execute(RENEW, List.of(key), token, Long.toString(ttlMillis));
        } catch (RuntimeException ignored) {
            // Readiness reports Redis failure; the task keeps its current DB transaction boundary.
        }
    }

    @PreDestroy
    void shutdownWatchdog() {
        watchdog.shutdownNow();
    }
}
