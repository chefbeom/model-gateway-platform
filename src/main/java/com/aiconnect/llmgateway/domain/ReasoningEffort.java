package com.aiconnect.llmgateway.domain;

/**
 * Logical-service policy for OpenAI Chat Completions reasoning effort.
 * REQUEST leaves the incoming request and provider/model default unchanged.
 */
public enum ReasoningEffort {
    REQUEST,
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    public String requestValue() {
        return this == REQUEST ? null : name().toLowerCase(java.util.Locale.ROOT);
    }
}
