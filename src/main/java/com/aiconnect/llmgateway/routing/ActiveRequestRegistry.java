package com.aiconnect.llmgateway.routing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class ActiveRequestRegistry {
    private final ActiveRequestStore store;

    public ActiveRequestRegistry() {
        this.store = new LocalActiveRequestStore();
    }

    @Autowired
    public ActiveRequestRegistry(ActiveRequestStore store) {
        this.store = store;
    }

    public int count(UUID deploymentId) { return store.count(deploymentId); }
    public boolean tryAcquire(UUID deploymentId, int maxConcurrency) {
        return store.tryAcquire(deploymentId, maxConcurrency);
    }
    public void release(UUID deploymentId) {
        store.release(deploymentId);
    }
}
