package com.aiconnect.llmgateway.routing;

import java.util.UUID;

public interface ActiveRequestStore {
    int count(UUID deploymentId);
    boolean tryAcquire(UUID deploymentId, int maxConcurrency);
    void release(UUID deploymentId);
}
