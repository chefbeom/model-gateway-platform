package com.aiconnect.llmgateway.retention;

import com.aiconnect.llmgateway.cluster.ClusterTaskCoordinator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RequestContentPurgeScheduler {
    private final RequestContentService content;
    private final ClusterTaskCoordinator coordinator;

    public RequestContentPurgeScheduler(RequestContentService content, ClusterTaskCoordinator coordinator) {
        this.content = content;
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${gateway.content-retention-purge-delay-ms:3600000}")
    public void purgeExpiredContent() {
        coordinator.runIfLeader("content-retention-purge", content::purgeExpired);
    }
}
