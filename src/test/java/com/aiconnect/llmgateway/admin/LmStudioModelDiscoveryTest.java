package com.aiconnect.llmgateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LmStudioModelDiscoveryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LmStudioModelDiscovery discovery = new LmStudioModelDiscovery(objectMapper);

    @Test
    void mapsNativeV1InstancesAndCapabilitiesWithoutGpuAssumptions() throws Exception {
        String json = """
                {"models":[{
                  "type":"llm","key":"google/gemma-future","display_name":"Future Gemma","architecture":"gemma-next",
                  "quantization":{"name":"Q8_0"},"max_context_length":131072,
                  "loaded_instances":[{"id":"gemma-instance-a","config":{"context_length":32768,"parallel":4}}],
                  "capabilities":{"vision":true,"trained_for_tool_use":true,"reasoning":{"default":"on"}}
                }]}
                """;

        DiscoveredRuntimeModel model = discovery.discover(objectMapper.readTree(json)).get(0);
        assertThat(model.providerModelId()).isEqualTo("gemma-instance-a");
        assertThat(model.compatibilityKey()).isEqualTo("google/gemma-future");
        assertThat(model.contextLength()).isEqualTo(32768);
        assertThat(model.maxConcurrency()).isEqualTo(4);
        assertThat(model.quantization()).isEqualTo("Q8_0");
        assertThat(model.capabilitiesJson()).contains("CHAT_COMPLETION", "STREAMING", "VISION", "TOOL_CALLING", "REASONING");
    }

    @Test
    void keepsDownloadedButUnloadedModelsOutOfReadyState() throws Exception {
        String json = "{\"models\":[{\"type\":\"llm\",\"key\":\"future/model\",\"display_name\":\"Future\",\"loaded_instances\":[],\"max_context_length\":8192}]}";
        DiscoveredRuntimeModel model = discovery.discover(objectMapper.readTree(json)).get(0);
        assertThat(model.loaded()).isFalse();
        assertThat(model.providerModelId()).isEqualTo("future/model");
    }

    @Test
    void acceptsOpenAiAndLegacyDataShapeAsFallback() throws Exception {
        String json = "{\"data\":[{\"id\":\"legacy-model\",\"type\":\"vlm\",\"state\":\"loaded\",\"max_context_length\":4096,\"capabilities\":[\"tool_use\"]}]}";
        DiscoveredRuntimeModel model = discovery.discover(objectMapper.readTree(json)).get(0);
        assertThat(model.loaded()).isTrue();
        assertThat(model.capabilitiesJson()).contains("VISION", "TOOL_CALLING");
    }
}
