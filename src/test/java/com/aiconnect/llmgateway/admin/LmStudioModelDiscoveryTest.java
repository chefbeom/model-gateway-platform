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
    void preservesQuantizationVariantsAndSelectedVariantInMetadata() throws Exception {
        String json = """
                {"models":[{
                  "type":"llm","key":"google/gemma-4-12b","display_name":"Gemma 4 12B",
                  "architecture":"gemma4","quantization":{"name":"Q8_0","bits_per_weight":8},
                  "variants":["google/gemma-4-12b@q4_k_m","google/gemma-4-12b@q8_0"],
                  "selected_variant":"google/gemma-4-12b@q8_0",
                  "loaded_instances":[],"max_context_length":104983,
                  "capabilities":{"vision":true,"trained_for_tool_use":true}
                }]}
                """;

        DiscoveredRuntimeModel model = discovery.discover(objectMapper.readTree(json)).get(0);
        assertThat(model.quantization()).isEqualTo("Q8_0");
        assertThat(model.metadataJson()).contains("google/gemma-4-12b@q4_k_m", "google/gemma-4-12b@q8_0", "selected_variant");
        assertThat(model.capabilitiesJson()).contains("VISION", "TOOL_CALLING");
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

    @Test
    void prefersOpenAiDataWhenLlamaCppReturnsBothModelsAndDataArrays() throws Exception {
        String json = """
                {"models":[{"name":"/opt/llm/models/gemma.gguf","model":"/opt/llm/models/gemma.gguf","type":"model","capabilities":["completion"]}],
                 "object":"list",
                 "data":[{"id":"/opt/llm/models/gemma.gguf","object":"model","owned_by":"llamacpp",
                   "meta":{"n_ctx":8192,"n_ctx_train":262144,"ftype":"Q4_0","families":[""]}}]}
                """;

        var models = discovery.discover(objectMapper.readTree(json));

        assertThat(models).hasSize(1);
        DiscoveredRuntimeModel model = models.get(0);
        assertThat(model.providerModelId()).isEqualTo("/opt/llm/models/gemma.gguf");
        assertThat(model.loaded()).isTrue();
        assertThat(model.contextLength()).isEqualTo(8192);
        assertThat(model.quantization()).isEqualTo("Q4_0");
        assertThat(model.capabilitiesJson()).contains("CHAT_COMPLETION", "STREAMING");
    }
}
