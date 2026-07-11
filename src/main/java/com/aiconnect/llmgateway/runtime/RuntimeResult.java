package com.aiconnect.llmgateway.runtime;

import com.fasterxml.jackson.databind.JsonNode;

public record RuntimeResult(int statusCode, JsonNode body) {
    public boolean isSuccessful() { return statusCode >= 200 && statusCode < 300; }
}
