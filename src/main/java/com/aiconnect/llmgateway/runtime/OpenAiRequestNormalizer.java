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
        return forExternalProvider(request, false);
    }

    /** Keeps an explicitly configured temperature while still removing other unsupported sampling controls. */
    public static JsonNode forExternalProvider(JsonNode request, boolean preserveTemperature) {
        if (!(request instanceof ObjectNode object)) {
            return request;
        }

        ObjectNode normalized = object.deepCopy();
        JsonNode legacy = normalized.remove("max_tokens");
        if (legacy != null && !normalized.has("max_completion_tokens")) {
            normalized.set("max_completion_tokens", legacy);
        }

        // GPT-5/o-series models reject sampling controls that LM Studio accepts.
        // GPT-6 accepts those controls only when reasoning effort is explicitly
        // disabled; an omitted effort uses the model's reasoning default.
        String model = normalized.path("model").asText("").toLowerCase(Locale.ROOT);
        String reasoningEffort = normalized.path("reasoning_effort").asText("");
        boolean reasoningEnabled = !reasoningEffort.isBlank() && !"none".equalsIgnoreCase(reasoningEffort);
        boolean gpt6UsesReasoning = model.startsWith("gpt-6") && !"none".equalsIgnoreCase(reasoningEffort);
        if (isReasoningModel(model) || reasoningEnabled || gpt6UsesReasoning) {
            if (!preserveTemperature || gpt6UsesReasoning) normalized.remove("temperature");
            normalized.remove(Set.of(
                    "top_p", "presence_penalty", "frequency_penalty",
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
