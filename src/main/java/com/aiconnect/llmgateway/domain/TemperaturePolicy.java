package com.aiconnect.llmgateway.domain;

/** Controls how the public request's temperature parameter is sent to a target. */
public enum TemperaturePolicy {
    /** Preserve the caller's value and keep existing provider compatibility handling. */
    REQUEST,
    /** Replace the caller's value with the configured value. */
    FIXED,
    /** Remove temperature so the target uses its own default. */
    OMIT
}
