package com.aiconnect.llmgateway.retention;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RequestContentPurgeScheduler {
    private final RequestContentService content;

    public RequestContentPurgeScheduler(RequestContentService content) {
        this.content = content;
    }

    @Scheduled(fixedDelayString = "${gateway.content-retention-purge-delay-ms:3600000}")
    public void purgeExpiredContent() {
        content.purgeExpired();
    }
}
