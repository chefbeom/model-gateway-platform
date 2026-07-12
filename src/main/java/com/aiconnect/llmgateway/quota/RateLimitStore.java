package com.aiconnect.llmgateway.quota;

import java.time.Instant;
import java.util.UUID;

public interface RateLimitStore {
    boolean tryAcquire(UUID apiKeyId, int limit, Instant now);
}
