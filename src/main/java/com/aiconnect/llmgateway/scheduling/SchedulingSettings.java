package com.aiconnect.llmgateway.scheduling;
import jakarta.validation.constraints.*;
public record SchedulingSettings(@NotNull SchedulingMode mode,
        @Min(0) @Max(60000) long localWaitMs, @Min(100) @Max(60000) long maxWaitMs,
        @Min(1) @Max(500) int maxQueuedRequests, @Min(1024) @Max(134217728) long maxQueuedBytes,
        @Min(1) @Max(500) int maxQueuedPerKey) {
    public static SchedulingSettings defaults() { return new SchedulingSettings(SchedulingMode.IMMEDIATE, 5000, 30000, 100, 33554432, 10); }
    public void validate() {
        if (mode == null || localWaitMs < 0 || localWaitMs>60000 || maxWaitMs < 100 || maxWaitMs > 60000 || (mode==SchedulingMode.LOCAL_FIRST_WAIT&&localWaitMs > maxWaitMs)
                || maxQueuedRequests < 1 || maxQueuedRequests > 500 || maxQueuedBytes < 1024 || maxQueuedBytes > 134217728
                || maxQueuedPerKey < 1 || maxQueuedPerKey > maxQueuedRequests)
            throw new IllegalArgumentException("대기시간은 최대 60초이며 로컬 대기시간·키별 한도는 전체 한도를 넘을 수 없습니다.");
    }
}
