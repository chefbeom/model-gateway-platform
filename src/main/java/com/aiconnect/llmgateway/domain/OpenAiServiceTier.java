package com.aiconnect.llmgateway.domain;

/** Default OpenAI-compatible processing tier configured for one provider model. */
public enum OpenAiServiceTier {
    REQUEST,
    STANDARD,
    FAST;

    public String requestValue() {
        return switch (this) {
            case REQUEST -> null;
            case STANDARD -> "default";
            case FAST -> "fast";
        };
    }
}
