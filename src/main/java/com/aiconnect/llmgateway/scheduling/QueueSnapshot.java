package com.aiconnect.llmgateway.scheduling;
public record QueueSnapshot(int queuedRequests, long queuedBytes, long oldestWaitMs) { }
