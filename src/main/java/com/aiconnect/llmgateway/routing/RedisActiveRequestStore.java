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
import com.aiconnect.llmgateway.scheduling.*;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private static final String QUEUE = "aiconnect:scheduler:pending";
    private static final String ENTRIES = "aiconnect:scheduler:metadata";
    private static final String CLEAN = """
            local function clean(now)
              local live = {}
              for _, id in ipairs(redis.call('ZRANGE', KEYS[1], 0, -1)) do
                local raw = redis.call('HGET', KEYS[2], id)
                local item = raw and cjson.decode(raw) or nil
                if not item or tonumber(item.deadline) <= now then
                  redis.call('ZREM', KEYS[1], id); redis.call('HDEL', KEYS[2], id)
                else table.insert(live, item) end
              end
              return live
            end
            """;
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(CLEAN + """
            local live = clean(tonumber(ARGV[8]))
            if ARGV[1] ~= '' and redis.call('HEXISTS', KEYS[2], ARGV[1]) == 0 then return 0 end
            for _, item in ipairs(live) do
              if item.id == ARGV[1] then break end
              for _, resource in ipairs(item.resources) do
                if resource == ARGV[2] or (ARGV[3] ~= '' and resource == ARGV[3]) then return 0 end
              end
            end
            local function total(index)
              local n = 0
              for _, key in ipairs(redis.call('SMEMBERS', index)) do
                local value = redis.call('GET', key)
                if value then n = n + tonumber(value) else redis.call('SREM', index, key) end
              end
              return n
            end
            if total(KEYS[3]) >= tonumber(ARGV[4]) then return 0 end
            if ARGV[3] ~= '' and total(KEYS[5]) >= tonumber(ARGV[5]) then return 0 end
            local function acquire(index, counter)
              redis.call('INCR', counter); redis.call('PEXPIRE', counter, ARGV[6])
              redis.call('SADD', index, counter); redis.call('PEXPIRE', index, tonumber(ARGV[6]) * 2)
            end
            acquire(KEYS[3], KEYS[4])
            if ARGV[3] ~= '' then acquire(KEYS[5], KEYS[6]) end
            if ARGV[1] ~= '' then redis.call('ZREM', KEYS[1], ARGV[1]); redis.call('HDEL', KEYS[2], ARGV[1]) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<String> ENQUEUE = new DefaultRedisScript<>(CLEAN + """
            local live = clean(tonumber(ARGV[2])); local item = cjson.decode(ARGV[1])
            if redis.call('HEXISTS', KEYS[2], item.id) == 1 then return '' end
            local count = 0; local keyCount = 0; local bytes = 0; local allBytes = 0
            for _, old in ipairs(live) do
              allBytes = allBytes + tonumber(old.bytes)
              if old.serviceId == item.serviceId then
                count = count + 1; bytes = bytes + tonumber(old.bytes)
                if old.keyId == item.keyId then keyCount = keyCount + 1 end
              end
            end
            if #live >= 500 or allBytes + tonumber(item.bytes) > 134217728 then return 'QUEUE_GLOBAL_FULL' end
            if count >= tonumber(ARGV[3]) or bytes + tonumber(item.bytes) > tonumber(ARGV[4]) then return 'QUEUE_FULL' end
            if keyCount >= tonumber(ARGV[5]) then return 'QUEUE_KEY_LIMIT' end
            local sequence = redis.call('INCR', KEYS[3])
            redis.call('HSET', KEYS[2], item.id, ARGV[1]); redis.call('ZADD', KEYS[1], sequence, item.id)
            redis.call('PEXPIRE', KEYS[1], 120000); redis.call('PEXPIRE', KEYS[2], 120000)
            return ''
            """, String.class);
    private static final DefaultRedisScript<Long> UPDATE = new DefaultRedisScript<>("""
            if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[2]); return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            redis.call('ZREM', KEYS[1], ARGV[1]); return redis.call('HDEL', KEYS[2], ARGV[1])
            """, Long.class);
    private static final DefaultRedisScript<String> SNAPSHOT = new DefaultRedisScript<>(CLEAN + """
            local live = clean(tonumber(ARGV[2])); local count = 0; local bytes = 0; local oldest = tonumber(ARGV[2])
            for _, item in ipairs(live) do
              if item.serviceId == ARGV[1] then count = count + 1; bytes = bytes + tonumber(item.bytes); oldest = math.min(oldest, tonumber(item.queuedAt)) end
            end
            return cjson.encode({queuedRequests=count, queuedBytes=bytes, oldestWaitMs=tonumber(ARGV[2])-oldest})
            """, String.class);
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
    private final ConcurrentHashMap<String, AtomicInteger> localActive = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisActiveRequestStore(StringRedisTemplate redis, DeploymentProfileProperties properties) {
        this.redis = redis;
        this.instanceId = properties.getInstanceId();
        this.leaseTtl = properties.getActiveRequestCounterTtl();
    }

    @Override
    public int count(UUID deploymentId) {
        Long result = redis.execute(COUNT, List.of(indexKey(deploymentId.toString())));
        return result == null ? 0 : Math.toIntExact(result);
    }

    @Override
    public boolean tryAcquire(UUID deploymentId, int maxConcurrency) {
        return tryAcquire(deploymentId, maxConcurrency, null, 0, null);
    }
    @Override public int runtimeCount(UUID id) {
        Long result = redis.execute(COUNT, List.of(indexKey("runtime:" + id)));
        return result == null ? 0 : Math.toIntExact(result);
    }
    @Override public boolean tryAcquire(UUID deploymentId, int maxConcurrency, UUID runtimeId, int runtimeLimit, String ticket) {
        String d = deploymentId.toString(), r = runtimeId == null ? "unused" : "runtime:" + runtimeId;
        Long result = redis.execute(ACQUIRE, List.of(QUEUE, ENTRIES, indexKey(d), instanceKey(d), indexKey(r), instanceKey(r)),
                ticket == null ? "" : ticket, "d:" + d, runtimeId == null ? "" : "r:" + runtimeId,
                Integer.toString(maxConcurrency), Integer.toString(runtimeLimit), Long.toString(leaseTtl.toMillis()), "", Long.toString(System.currentTimeMillis()));
        if (result == null || result <= 0) return false;
        localActive.computeIfAbsent(d, ignored -> new AtomicInteger()).incrementAndGet();
        if (runtimeId != null) localActive.computeIfAbsent(r, ignored -> new AtomicInteger()).incrementAndGet();
        return true;
    }

    @Override
    public void release(UUID deploymentId) {
        release(deploymentId, null);
    }
    @Override public void release(UUID deploymentId, UUID runtimeId) {
        try {releaseResource(deploymentId.toString());}
        finally {if (runtimeId != null) releaseResource("runtime:" + runtimeId);}
    }
    private void releaseResource(String resource) {
        try { redis.execute(RELEASE, List.of(indexKey(resource), instanceKey(resource)),
                Long.toString(leaseTtl.toMillis()));
        } finally { localActive.computeIfPresent(resource, (ignored, counter) ->
                counter.updateAndGet(value -> Math.max(0, value - 1)) == 0 ? null : counter); }
    }
    @Override public String enqueue(QueueTicket ticket, SchedulingSettings limits) {
        String result = redis.execute(ENQUEUE, List.of(QUEUE, ENTRIES, "aiconnect:scheduler:sequence"), json(ticket),
                Long.toString(System.currentTimeMillis()), Integer.toString(limits.maxQueuedRequests()),
                Long.toString(limits.maxQueuedBytes()), Integer.toString(limits.maxQueuedPerKey()));
        if (result == null) throw new IllegalStateException("Queue admission was not acknowledged");
        return result.isEmpty() ? null : result;
    }
    @Override public void updateTicket(QueueTicket ticket) { redis.execute(UPDATE, List.of(QUEUE, ENTRIES), ticket.id(), json(ticket)); }
    @Override public void removeTicket(String id) { redis.execute(REMOVE, List.of(QUEUE, ENTRIES), id); }
    @Override public QueueSnapshot snapshot(UUID service) {
        try { return mapper.readValue(redis.execute(SNAPSHOT, List.of(QUEUE, ENTRIES), service.toString(), Long.toString(System.currentTimeMillis())), QueueSnapshot.class); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Unable to read queue metadata", exception); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException exception) { throw new IllegalArgumentException("Invalid queue metadata", exception); }
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

    private String indexKey(String deploymentId) {
        return "aiconnect:active-requests:index:" + deploymentId;
    }

    private String instanceKey(String deploymentId) {
        return "aiconnect:active-requests:" + deploymentId + ":" + instanceId;
    }
}
