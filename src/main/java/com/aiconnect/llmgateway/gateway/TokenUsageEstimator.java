package com.aiconnect.llmgateway.gateway;

import com.fasterxml.jackson.databind.JsonNode;

/** Small, provider-neutral fallback for runtimes that omit OpenAI usage metadata. */
public final class TokenUsageEstimator {
    private TokenUsageEstimator() { }

    public static int estimateInputTokens(JsonNode request) {
        JsonNode messages = request == null ? null : request.get("messages");
        if (messages == null || !messages.isArray()) return 0;
        return estimateTextTokens(messages.toString());
    }

    public static int estimateOutputTokens(JsonNode response) {
        StringBuilder text = new StringBuilder();
        appendOutputText(response, text);
        return estimateTextTokens(text.toString());
    }

    public static int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(0, (text.length() + 3) / 4);
    }

    public static void appendOutputText(JsonNode response, StringBuilder output) {
        if (response == null || output == null) return;
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray()) {
            for (JsonNode choice : choices) {
                appendContent(choice.path("message").get("content"), output);
                appendContent(choice.path("delta").get("content"), output);
                appendContent(choice.get("text"), output);
            }
            return;
        }
        appendContent(response.get("content"), output);
        appendContent(response.get("response"), output);
    }

    private static void appendContent(JsonNode node, StringBuilder output) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            output.append(node.textValue());
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) appendContent(item, output);
            return;
        }
        if (node.isObject()) {
            appendContent(node.get("text"), output);
        }
    }
}
