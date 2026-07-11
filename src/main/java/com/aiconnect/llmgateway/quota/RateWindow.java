package com.aiconnect.llmgateway.quota;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

final class RateWindow {
    private final Deque<Instant> requests = new ArrayDeque<>();
    synchronized boolean tryAcquire(int limit, Instant now) {
        Instant cutoff = now.minusSeconds(60);
        while (!requests.isEmpty() && requests.peekFirst().isBefore(cutoff)) requests.removeFirst();
        if (requests.size() >= limit) return false;
        requests.addLast(now);
        return true;
    }
}
