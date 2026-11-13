package com.aiconnect.llmgateway.playground;

import com.fasterxml.jackson.databind.JsonNode;

/** Safe response metadata; reasoning text and other raw response content are never retained. */
record PlaygroundResponseInfo(String finishReason, boolean reasoningPresent) {
    static PlaygroundResponseInfo from(JsonNode choice, boolean streaming) {
        JsonNode message = choice.path(streaming ? "delta" : "message");
        return new PlaygroundResponseInfo(safeReason(choice.path("finish_reason")),
                present(message.path("reasoning_content")) || present(message.path("reasoning")));
    }

    PlaygroundResponseInfo merge(PlaygroundResponseInfo next) {
        return new PlaygroundResponseInfo(next.finishReason != null ? next.finishReason : finishReason,
                reasoningPresent || next.reasoningPresent);
    }

    private static boolean present(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return false;
        if (value.isTextual()) return !value.asText().isBlank();
        return value.isContainerNode() && !value.isEmpty();
    }

    private static String safeReason(JsonNode value) {
        if (!value.isTextual()) return null;
        String reason = value.asText();
        return reason.matches("[a-zA-Z0-9_-]{1,64}") ? reason : null;
    }
}
