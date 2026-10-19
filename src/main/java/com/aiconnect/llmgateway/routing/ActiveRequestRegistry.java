package com.aiconnect.llmgateway.routing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import com.aiconnect.llmgateway.scheduling.*;

@Component
public class ActiveRequestRegistry {
    private final ActiveRequestStore store;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

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
        signal();
    }
    public int runtimeCount(UUID id) { return store.runtimeCount(id); }
    public boolean tryAcquire(UUID deployment, int limit, UUID runtime, int runtimeLimit, String ticket) { return store.tryAcquire(deployment, limit, runtime, runtimeLimit, ticket); }
    public void release(UUID deployment, UUID runtime) { store.release(deployment, runtime); signal(); }
    public String enqueue(QueueTicket ticket, SchedulingSettings limits) { return store.enqueue(ticket, limits); }
    public void updateTicket(QueueTicket ticket) { store.updateTicket(ticket); }
    public void removeTicket(String id) { store.removeTicket(id); signal(); }
    public QueueSnapshot snapshot(UUID service) { return store.snapshot(service); }
    public void onCapacityAvailable(Runnable listener) { listeners.add(listener); }
    public void signal() { listeners.forEach(listener->{try{listener.run();}catch(RuntimeException ignored){ /* Polling remains the fallback. */ }}); }
    public static final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable release;
        public Lease(Runnable release) { this.release = release; }
        @Override public void close() { if (closed.compareAndSet(false, true)) release.run(); }
    }
}
