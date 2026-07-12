package com.aiconnect.llmgateway.quota;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRateLimitStoreTest {
    @Test
    void enforcesLimitPerApiKeyAcrossRollingMinute() {
        LocalRateLimitStore store = new LocalRateLimitStore();
        UUID key = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-12T00:00:00Z");

        assertTrue(store.tryAcquire(key, 2, now));
        assertTrue(store.tryAcquire(key, 2, now.plusSeconds(1)));
        assertFalse(store.tryAcquire(key, 2, now.plusSeconds(2)));
        assertTrue(store.tryAcquire(key, 2, now.plusSeconds(61)));
    }
}
