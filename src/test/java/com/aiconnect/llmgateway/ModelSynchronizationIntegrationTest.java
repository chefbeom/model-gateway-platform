package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_model_sync;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ModelSynchronizationIntegrationTest {
    @Autowired ControlPlaneService controlPlane;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository deployments;
    @MockitoBean InferenceRuntimeClient runtimeClient;

    @Test
    void createsAndUpdatesDeploymentFromNativeLmStudioMetadata() throws Exception {
        Organization organization = organizations.save(new Organization("Discovery Org"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "future-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://future-node:1234", null));

        when(runtimeClient.listModels(any(RuntimeEndpoint.class)))
                .thenReturn(new RuntimeResult(200, objectMapper.readTree(nativeResponse(32768, 4))));
        assertThat(controlPlane.syncModels(endpoint.getId())).hasSize(1);

        ModelDeployment created = deployments.findByRuntimeEndpointId(endpoint.getId()).get(0);
        assertThat(created.getProviderModelId()).isEqualTo("future-instance");
        assertThat(created.getCompatibilityKey()).isEqualTo("vendor/future-model");
        assertThat(created.getContextLength()).isEqualTo(32768);
        assertThat(created.getMaxConcurrency()).isEqualTo(4);
        assertThat(created.getQuantization()).isEqualTo("Q6_K");
        assertThat(created.getCapabilitiesJson()).contains("VISION", "TOOL_CALLING");

        when(runtimeClient.listModels(any(RuntimeEndpoint.class)))
                .thenReturn(new RuntimeResult(200, objectMapper.readTree(nativeResponse(65536, 2))));
        assertThat(controlPlane.syncModels(endpoint.getId())).isEmpty();
        ModelDeployment updated = deployments.findById(created.getId()).orElseThrow();
        assertThat(updated.getContextLength()).isEqualTo(65536);
        assertThat(updated.getMaxConcurrency()).isEqualTo(2);
    }

    private String nativeResponse(int contextLength, int parallel) {
        return """
                {"models":[{
                  "type":"llm","key":"vendor/future-model","display_name":"Future Model","architecture":"future-arch",
                  "quantization":{"name":"Q6_K"},"max_context_length":131072,
                  "loaded_instances":[{"id":"future-instance","config":{"context_length":%d,"parallel":%d}}],
                  "capabilities":{"vision":true,"trained_for_tool_use":true}
                }]}
                """.formatted(contextLength, parallel);
    }
}
