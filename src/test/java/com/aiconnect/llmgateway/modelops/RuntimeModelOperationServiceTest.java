package com.aiconnect.llmgateway.modelops;

import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.routing.ActiveRequestRegistry;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeModelOperationServiceTest {
    private final RuntimeEndpointRepository endpoints = mock(RuntimeEndpointRepository.class);
    private final ModelDeploymentRepository deployments = mock(ModelDeploymentRepository.class);
    private final RuntimeModelProfileRepository profiles = mock(RuntimeModelProfileRepository.class);
    private final RuntimeModelOperationRepository operations = mock(RuntimeModelOperationRepository.class);
    private final LmStudioModelManagementClient models = mock(LmStudioModelManagementClient.class);
    private final ControlPlaneService controlPlane = mock(ControlPlaneService.class);
    private final InferenceRuntimeClient inference = mock(InferenceRuntimeClient.class);
    private final ActiveRequestRegistry active = mock(ActiveRequestRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeModelOperationService service = new RuntimeModelOperationService(endpoints, deployments, profiles,
            operations, models, controlPlane, inference, active, mapper);

    @Test
    void preflightReportsWhenRequestedVariantIsNotTheRuntimeSelectedVariant() throws Exception {
        UUID endpointId = UUID.randomUUID();
        RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO, "http://gpu:1234", null);
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(models.list(any(RuntimeEndpoint.class))).thenReturn(new RuntimeResult(200, mapper.readTree("""
                {"models":[{"type":"llm","key":"google/gemma-4-12b","display_name":"Gemma 4 12B",
                  "variants":["google/gemma-4-12b@q4_k_m","google/gemma-4-12b@q8_0"],
                  "selected_variant":"google/gemma-4-12b@q8_0","max_context_length":104983,
                  "size_bytes":1000,"loaded_instances":[]}]}
                """)));

        RuntimeModelOperationService.PreflightResult result = service.preflight(endpointId,
                new RuntimeModelOperationService.LoadCommand("google/gemma-4-12b", "google/gemma-4-12b@q4_k_m",
                        8192, 512, null, null, null, true, true, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(result.compatible()).isFalse();
        assertThat(result.variantAvailable()).isTrue();
        assertThat(result.variantSelectionSupported()).isFalse();
        assertThat(result.selectedVariant()).isEqualTo("google/gemma-4-12b@q8_0");
        assertThat(result.warnings()).anyMatch(value -> value.contains("cannot switch Q4/Q8"));
    }

    @Test
    void preflightListsNativeOptionsAndWarnsForAgentOnlyOptions() throws Exception {
        UUID endpointId = UUID.randomUUID();
        RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO, "http://gpu:1234", null);
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(models.list(any(RuntimeEndpoint.class))).thenReturn(new RuntimeResult(200, mapper.readTree("""
                {"models":[{"type":"llm","key":"qwen/qwen3-4b","display_name":"Qwen 3 4B",
                  "max_context_length":32768,"size_bytes":1000,"loaded_instances":[]}]}
                """)));

        RuntimeModelOperationService.PreflightResult result = service.preflight(endpointId,
                new RuntimeModelOperationService.LoadCommand("qwen/qwen3-4b", null, 8192, 512, 512, 4, null,
                        true, true, 48, 3600, null, "max", null, 8, true, null, null, true, true, 42, "Q8_0", "Q8_0"));

        assertThat(result.compatible()).isTrue();
        assertThat(result.nativeSupportedOptions()).containsExactly("context_length", "eval_batch_size", "flash_attention", "num_experts", "offload_kv_cache_to_gpu");
        assertThat(result.warnings()).anyMatch(value -> value.contains("Physical Batch Size"));
        assertThat(result.warnings()).anyMatch(value -> value.contains("GPU offload"));
        assertThat(result.warnings()).anyMatch(value -> value.contains("CPU Thread Pool"));
    }
}