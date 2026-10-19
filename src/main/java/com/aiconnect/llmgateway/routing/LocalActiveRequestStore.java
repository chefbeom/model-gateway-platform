package com.aiconnect.llmgateway.routing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import com.aiconnect.llmgateway.scheduling.*;

@Component
@ConditionalOnProperty(name = "aiconnect.deployment.shared-state-provider", havingValue = "LOCAL", matchIfMissing = true)
public class LocalActiveRequestStore implements ActiveRequestStore {
    private final Map<String, Integer> active = new HashMap<>();
    private final LinkedHashMap<String, QueueTicket> pending = new LinkedHashMap<>();
    @Override public synchronized int count(UUID id) { return active.getOrDefault("d:" + id, 0); }
    @Override public synchronized int runtimeCount(UUID id) { return active.getOrDefault("r:" + id, 0); }
    @Override public boolean tryAcquire(UUID id, int limit) { return tryAcquire(id, limit, null, 0, null); }
    @Override public synchronized boolean tryAcquire(UUID id, int limit, UUID runtime, int runtimeLimit, String ticketId) {
        clean();
        String d = "d:" + id, r = runtime == null ? "" : "r:" + runtime;
        if (ticketId != null && !pending.containsKey(ticketId)) return false;
        for (QueueTicket earlier : pending.values()) {
            if (earlier.id().equals(ticketId)) break;
            if (earlier.competes(d, r)) return false;
        }
        if (active.getOrDefault(d, 0) >= limit || (runtime != null && active.getOrDefault(r, 0) >= runtimeLimit)) return false;
        active.merge(d, 1, Integer::sum);
        if (runtime != null) active.merge(r, 1, Integer::sum);
        if (ticketId != null) pending.remove(ticketId);
        return true;
    }
    @Override public void release(UUID id) { release(id, null); }
    @Override public synchronized void release(UUID id, UUID runtime) { decrement("d:" + id); if (runtime != null) decrement("r:" + runtime); }
    private void decrement(String key) { active.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1); }
    @Override public synchronized String enqueue(QueueTicket ticket, SchedulingSettings limits) {
        clean();
        if (pending.containsKey(ticket.id())) return null;
        long count = 0, keys = 0, bytes = 0, allBytes = 0;
        for (QueueTicket item : pending.values()) {
            allBytes += item.bytes();
            if (item.serviceId().equals(ticket.serviceId())) { count++; bytes += item.bytes(); if (item.keyId().equals(ticket.keyId())) keys++; }
        }
        if (pending.size() >= 500 || allBytes + ticket.bytes() > 134217728L) return "QUEUE_GLOBAL_FULL";
        if (count >= limits.maxQueuedRequests() || bytes + ticket.bytes() > limits.maxQueuedBytes()) return "QUEUE_FULL";
        if (keys >= limits.maxQueuedPerKey()) return "QUEUE_KEY_LIMIT";
        pending.put(ticket.id(), ticket); return null;
    }
    @Override public synchronized void updateTicket(QueueTicket ticket) { clean(); if (pending.containsKey(ticket.id())) pending.put(ticket.id(), ticket); }
    @Override public synchronized void removeTicket(String id) { pending.remove(id); }
    @Override public synchronized QueueSnapshot snapshot(UUID service) {
        clean(); long bytes = 0, oldest = Long.MAX_VALUE; int count = 0;
        for (QueueTicket item : pending.values()) if (item.serviceId().equals(service)) { count++; bytes += item.bytes(); oldest = Math.min(oldest, item.queuedAt()); }
        return new QueueSnapshot(count, bytes, count == 0 ? 0 : Math.max(0, System.currentTimeMillis() - oldest));
    }
    private void clean() { long now = System.currentTimeMillis(); pending.values().removeIf(item -> item.deadline() <= now); }
}
