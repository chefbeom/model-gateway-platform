package com.aiconnect.llmgateway.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCapabilityDetectorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void detectsStructuredToolsAndVisionFromOpenAiMessageContent() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("""
                {
                  "response_format":{"type":"json_schema"},
                  "tools":[{"type":"function"}],
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"describe"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}
                  ]}]
                }
                """);
        assertThat(RequestCapabilityDetector.detect(request))
                .containsExactlyInAnyOrder("STRUCTURED_OUTPUT", "TOOL_CALLING", "VISION");
    }

    @Test
    void plainTextDoesNotRequireVisionOrTools() throws Exception {
        ObjectNode request = (ObjectNode) objectMapper.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        assertThat(RequestCapabilityDetector.detect(request)).isEmpty();
    }
}
