package com.aiconnect.llmgateway.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRequestNormalizerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsLegacyMaxTokensForExternalProviderWithoutMutatingCallerRequest() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {
                  "model":"provider-model",
                  "messages":[{"role":"user","content":"hello"}],
                  "max_tokens":4096,
                  "stream":false
                }
                """);

        JsonNode normalized = OpenAiRequestNormalizer.forExternalProvider(request);

        assertThat(normalized.path("max_completion_tokens").asInt()).isEqualTo(4096);
        assertThat(normalized.has("max_tokens")).isFalse();
        assertThat(request.path("max_tokens").asInt()).isEqualTo(4096);
    }

    @Test
    void keepsCanonicalValueWhenBothTokenFieldsArePresent() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {"max_tokens":1024,"max_completion_tokens":2048}
                """);

        JsonNode normalized = OpenAiRequestNormalizer.forExternalProvider(request);

        assertThat(normalized.path("max_completion_tokens").asInt()).isEqualTo(2048);
        assertThat(normalized.has("max_tokens")).isFalse();
    }

    @Test
    void removesUnsupportedSamplingControlsForGpt5WhilePreservingMessagesAndSchema() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {
                  "model":"gpt-5.6-luna",
                  "messages":[{"role":"user","content":"return JSON"}],
                  "temperature":0.2,
                  "top_p":0.9,
                  "presence_penalty":0.1,
                  "frequency_penalty":0.1,
                  "max_tokens":8192,
                  "response_format":{"type":"json_schema"},
                  "stream":false
                }
                """);

        JsonNode normalized = OpenAiRequestNormalizer.forExternalProvider(request);

        assertThat(normalized.has("temperature")).isFalse();
        assertThat(normalized.has("top_p")).isFalse();
        assertThat(normalized.has("presence_penalty")).isFalse();
        assertThat(normalized.has("frequency_penalty")).isFalse();
        assertThat(normalized.path("max_completion_tokens").asInt()).isEqualTo(8192);
        assertThat(normalized.has("response_format")).isTrue();
        assertThat(normalized.path("messages").isArray()).isTrue();
    }

    @Test
    void preservesSamplingControlsForNonReasoningExternalModels() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {"model":"gpt-4.1","temperature":0.2,"top_p":0.9}
                """);

        JsonNode normalized = OpenAiRequestNormalizer.forExternalProvider(request);

        assertThat(normalized.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(normalized.path("top_p").asDouble()).isEqualTo(0.9);
    }

    @Test
    void leavesNonObjectRequestsUntouched() throws Exception {
        JsonNode request = objectMapper.readTree("[1,2,3]");

        assertThat(OpenAiRequestNormalizer.forExternalProvider(request)).isSameAs(request);
    }
}
