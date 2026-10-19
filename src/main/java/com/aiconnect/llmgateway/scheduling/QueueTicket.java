package com.aiconnect.llmgateway.scheduling;
import java.util.List;
import java.util.UUID;
/** Identifiers and scheduling metadata only; never request content or headers. */
public record QueueTicket(String id, UUID serviceId, UUID keyId, long bytes, long queuedAt, long deadline, List<String> resources) {
    public boolean competes(String deployment, String runtime) {
        return resources.contains(deployment) || (runtime != null && !runtime.isEmpty() && resources.contains(runtime));
    }
}
