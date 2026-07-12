package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.cluster.DeploymentProfileProperties;
import com.aiconnect.llmgateway.cluster.RedisClusterTaskCoordinator;
import com.aiconnect.llmgateway.quota.RedisRateLimitStore;
import com.aiconnect.llmgateway.routing.RedisActiveRequestStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedisSharedStateIntegrationTest {
    @Test
    void rateLimitAndConcurrencyAreSharedAcrossGatewayInstances() throws Exception {
        String host = System.getenv("TEST_REDIS_HOST");
        assumeTrue(host != null && !host.isBlank(), "TEST_REDIS_HOST is required for the Redis integration gate");
        int port = Integer.parseInt(System.getenv().getOrDefault("TEST_REDIS_PORT", "6379"));

        LettuceConnectionFactory connection = new LettuceConnectionFactory(host, port);
        connection.afterPropertiesSet();
        connection.start();
        try {
            StringRedisTemplate redis = new StringRedisTemplate(connection);
            redis.afterPropertiesSet();

            UUID apiKeyId = UUID.randomUUID();
            RedisRateLimitStore rate1 = new RedisRateLimitStore(redis);
            RedisRateLimitStore rate2 = new RedisRateLimitStore(redis);
            Instant now = Instant.now();
            assertTrue(rate1.tryAcquire(apiKeyId, 2, now));
            assertTrue(rate2.tryAcquire(apiKeyId, 2, now.plusMillis(1)));
            assertFalse(rate1.tryAcquire(apiKeyId, 2, now.plusMillis(2)));

            UUID deploymentId = UUID.randomUUID();
            DeploymentProfileProperties gateway1 = properties("redis-test-1");
            DeploymentProfileProperties gateway2 = properties("redis-test-2");
            RedisActiveRequestStore active1 = new RedisActiveRequestStore(redis, gateway1);
            RedisActiveRequestStore active2 = new RedisActiveRequestStore(redis, gateway2);

            assertTrue(active1.tryAcquire(deploymentId, 2));
            assertTrue(active2.tryAcquire(deploymentId, 2));
            assertEquals(2, active1.count(deploymentId));
            assertFalse(active1.tryAcquire(deploymentId, 2));

            active1.release(deploymentId);
            assertEquals(1, active2.count(deploymentId));
            assertTrue(active2.tryAcquire(deploymentId, 2));
            active2.release(deploymentId);
            active2.release(deploymentId);
            assertEquals(0, active1.count(deploymentId));

            gateway1.setLockTtl(Duration.ofSeconds(3));
            gateway2.setLockTtl(Duration.ofSeconds(3));
            RedisClusterTaskCoordinator leader = new RedisClusterTaskCoordinator(redis, gateway1);
            RedisClusterTaskCoordinator follower = new RedisClusterTaskCoordinator(redis, gateway2);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var running = executor.submit(() -> leader.runIfLeader("integration-lock-" + deploymentId, () -> {
                    entered.countDown();
                    try { release.await(6, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                Thread.sleep(3_500);
                assertFalse(follower.runIfLeader("integration-lock-" + deploymentId, () -> {}));
                release.countDown();
                assertTrue(running.get(2, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        } finally {
            connection.destroy();
        }
    }

    private DeploymentProfileProperties properties(String instanceId) {
        DeploymentProfileProperties properties = new DeploymentProfileProperties();
        properties.setInstanceId(instanceId);
        properties.setActiveRequestCounterTtl(Duration.ofMinutes(2));
        return properties;
    }
}
