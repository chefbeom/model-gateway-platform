package com.aiconnect.llmgateway.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

public final class RequestCapabilityDetector {
    private RequestCapabilityDetector() { }

    public static Set<String> detect(ObjectNode request) {
        Set<String> capabilities = new HashSet<>();
        if (request.has("response_format")
                && "json_schema".equals(request.path("response_format").path("type").asText())) {
            capabilities.add("STRUCTURED_OUTPUT");
        }
        if (request.path("tools").isArray() && !request.path("tools").isEmpty()) capabilities.add("TOOL_CALLING");
        if (containsImage(request.path("messages")) || containsImage(request.path("input"))) capabilities.add("VISION");
        return capabilities;
    }

    private static boolean containsImage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return false;
        if (node.isArray()) {
            for (JsonNode child : node) if (containsImage(child)) return true;
            return false;
        }
        if (!node.isObject()) return false;
        String type = node.path("type").asText("");
        if (type.equals("image_url") || type.equals("input_image") || type.equals("image")) return true;
        if (node.has("image_url") || node.has("data_url")) return true;
        return containsImage(node.path("content"));
    }
}
