package com.aiconnect.llmgateway.quota;

/** Reset semantics for a spend quota. TOTAL never resets automatically. */
public enum SpendQuotaPeriod {
    DAILY,
    MONTHLY,
    TOTAL
}
