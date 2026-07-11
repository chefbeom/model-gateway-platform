package com.aiconnect.llmgateway.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;

public record StreamingGatewayResult(int statusCode, String requestId, InputStream stream, JsonNode error) {
    public boolean isSuccessful() { return stream != null; }
}
