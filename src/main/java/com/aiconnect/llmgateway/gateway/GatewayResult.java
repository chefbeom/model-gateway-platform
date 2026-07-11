package com.aiconnect.llmgateway.gateway;

import com.fasterxml.jackson.databind.JsonNode;

public record GatewayResult(int statusCode, JsonNode body, String requestId) { }
