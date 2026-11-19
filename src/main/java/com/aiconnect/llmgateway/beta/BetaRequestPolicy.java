package com.aiconnect.llmgateway.beta;

import com.aiconnect.llmgateway.gateway.RequestCapabilityDetector;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

/** Conservative defaults for the local-only invitation beta. */
public final class BetaRequestPolicy {
    private BetaRequestPolicy() { }
    public static void apply(ObjectNode request) {
        if (request.has("attachments") || request.has("audio") || request.has("file")) reject("BETA_MEDIA_UNAVAILABLE", "Beta media input is not enabled.");
        JsonNode messages=request.path("messages");
        if (messages.isArray()) for (JsonNode message : messages) {
            JsonNode content=message.path("content");
            if (message.has("audio") || message.has("file") || message.has("attachments") || message.has("image_url")) reject("BETA_MEDIA_UNAVAILABLE", "Beta media input is not enabled.");
            validateTextContent(content);
        }
        if (request.has("input")) validateTextContent(request.get("input"));
        if (RequestCapabilityDetector.detect(request).contains("VISION")) reject("BETA_MEDIA_UNAVAILABLE", "Beta media input is not enabled.");
        JsonNode n=request.get("n");
        if (n != null && (!n.isIntegralNumber() || !n.canConvertToInt() || n.asInt()!=1)) reject("BETA_CHOICES_LIMIT", "Beta requests allow one response.");
        JsonNode output=request.has("max_completion_tokens") ? request.get("max_completion_tokens") : request.get("max_tokens");
        if (output != null && (!output.isIntegralNumber() || !output.canConvertToInt() || output.asInt() < 1 || output.asInt() > 2048))
            reject("BETA_OUTPUT_LIMIT", "Beta output limit must be between 1 and 2048.");
        int effectiveLimit=output == null ? 2048 : output.asInt();
        request.remove("max_completion_tokens");
        request.put("max_tokens",effectiveLimit);
    }
    private static void validateTextContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull() || content.isTextual()) return;
        if (!content.isArray()) { reject("BETA_MEDIA_UNAVAILABLE", "Beta media input is not enabled."); return; }
        for (JsonNode part : content) {
            if (!part.isObject() || !"text".equals(part.path("type").asText()) || !part.path("text").isTextual()) reject("BETA_MEDIA_UNAVAILABLE", "Beta media input is not enabled.");
            var fields=part.fieldNames();
            while (fields.hasNext()) { String field=fields.next(); if (!field.equals("type") && !field.equals("text")) reject("BETA_MEDIA_UNAVAILABLE", "Beta media input is not enabled."); }
        }
    }
    private static void reject(String code,String message) { throw new ApiException(HttpStatus.BAD_REQUEST,code,message); }
}
