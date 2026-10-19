package com.aiconnect.llmgateway.routing;

import java.util.UUID;
import com.aiconnect.llmgateway.scheduling.*;

public interface ActiveRequestStore {
    int count(UUID deploymentId);
    boolean tryAcquire(UUID deploymentId, int maxConcurrency);
    void release(UUID deploymentId);
    default int runtimeCount(UUID runtimeId) { return 0; }
    default boolean tryAcquire(UUID deploymentId, int limit, UUID runtimeId, int runtimeLimit, String ticketId) { return tryAcquire(deploymentId, limit); }
    default void release(UUID deploymentId, UUID runtimeId) { release(deploymentId); }
    default String enqueue(QueueTicket ticket, SchedulingSettings limits) { throw new UnsupportedOperationException(); }
    default void updateTicket(QueueTicket ticket) { }
    default void removeTicket(String ticketId) { }
    default QueueSnapshot snapshot(UUID serviceId) { return new QueueSnapshot(0, 0, 0); }
}
