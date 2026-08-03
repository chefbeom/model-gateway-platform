package com.aiconnect.llmgateway.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/**
 * Normalizes the small set of request differences between OpenAI-compatible
 * runtimes. The public Gateway contract continues to accept the legacy
 * {@code max_tokens} field; current OpenAI chat models use
 * {@code max_completion_tokens} instead.
 *
 * <p>This adapter is used only by the external OpenAI runtime clients. Local
 * LM Studio requests continue to be forwarded unchanged.</p>
 */
public final class OpenAiRequestNormalizer {
    private OpenAiRequestNormalizer() {
    }

    /**
     * Returns a copy of the request suitable for an external OpenAI provider.
     * If both token-limit fields are supplied, the canonical
     * {@code max_completion_tokens} value wins.
     */
    public static JsonNode forExternalProvider(JsonNode request) {
        if (!(request instanceof ObjectNode object)) {
            return request;
        }

        ObjectNode normalized = object.deepCopy();
        JsonNode legacy = normalized.remove("max_tokens");
        if (legacy != null && !normalized.has("max_completion_tokens")) {
            normalized.set("max_completion_tokens", legacy);
        }

        // GPT-5/o-series models reject sampling controls that LM Studio accepts.
        // Omit these fields only at the external-provider boundary and retain
        // the public API contract for local runtimes.
        String model = normalized.path("model").asText("").toLowerCase(Locale.ROOT);
        if (isReasoningModel(model) || normalized.has("reasoning_effort")) {
            normalized.remove(Set.of(
                    "temperature", "top_p", "presence_penalty", "frequency_penalty",
                    "stop", "logprobs", "top_logprobs"
            ));
        }
        return normalized;
    }

    private static boolean isReasoningModel(String model) {
        return model.startsWith("gpt-5") || model.startsWith("o1")
                || model.startsWith("o3") || model.startsWith("o4");
    }
}
