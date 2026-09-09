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
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
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

    @Test
    void rebindsFollowTargetsWhenRuntimeModelChanges() throws Exception {
        Organization organization = organizations.save(new Organization("Target Rebind Org"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "target-rebind-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://target-rebind-node:1234", null));
        LlmService service = services.save(new LlmService(organization.getId(), "target-rebind-service", "Target Rebind",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));

        when(runtimeClient.listModels(any(RuntimeEndpoint.class)))
                .thenReturn(new RuntimeResult(200, objectMapper.readTree(nativeResponse("vendor/old-model", "old-instance", 32768, 2))));
        controlPlane.syncModels(endpoint.getId());
        ModelDeployment oldDeployment = deployments.findByRuntimeEndpointId(endpoint.getId()).get(0);
        ServiceTarget target = targets.save(new ServiceTarget(service.getId(), oldDeployment.getId(), 3, 70, true, 4));

        when(runtimeClient.listModels(any(RuntimeEndpoint.class)))
                .thenReturn(new RuntimeResult(200, objectMapper.readTree(nativeResponse("vendor/new-model", "new-instance", 65536, 4))));
        controlPlane.syncModels(endpoint.getId());

        ModelDeployment newDeployment = deployments.findByRuntimeEndpointId(endpoint.getId()).stream()
                .filter(item -> "new-instance".equals(item.getProviderModelId())).findFirst().orElseThrow();
        ServiceTarget rebound = targets.findById(target.getId()).orElseThrow();
        assertThat(rebound.getDeploymentId()).isEqualTo(newDeployment.getId());
        assertThat(rebound.getPriority()).isEqualTo(3);
        assertThat(rebound.getWeight()).isEqualTo(70);
        assertThat(rebound.isDegraded()).isTrue();
        assertThat(rebound.getMaxConcurrencyOverride()).isEqualTo(4);
        ModelDeployment oldAfterSync = deployments.findById(oldDeployment.getId()).orElseThrow();
        assertThat(oldAfterSync.isLoaded()).isFalse();
    }

    @Test
    void doesNotGuessWhenSeveralReplacementModelsAreLoaded() throws Exception {
        Organization organization = organizations.save(new Organization("Ambiguous Rebind Org"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "ambiguous-rebind-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, "http://ambiguous-rebind-node:1234", null));
        LlmService service = services.save(new LlmService(organization.getId(), "ambiguous-rebind-service", "Ambiguous Rebind",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));

        when(runtimeClient.listModels(any(RuntimeEndpoint.class)))
                .thenReturn(new RuntimeResult(200, objectMapper.readTree(nativeResponse("vendor/old-ambiguous", "old-ambiguous", 32768, 2))));
        controlPlane.syncModels(endpoint.getId());
        ModelDeployment oldDeployment = deployments.findByRuntimeEndpointId(endpoint.getId()).get(0);
        ServiceTarget target = targets.save(new ServiceTarget(service.getId(), oldDeployment.getId(), 1, 100, false, null));

        when(runtimeClient.listModels(any(RuntimeEndpoint.class)))
                .thenReturn(new RuntimeResult(200, objectMapper.readTree(nativeResponseWithTwoLoadedModels())));
        controlPlane.syncModels(endpoint.getId());

        ServiceTarget unchanged = targets.findById(target.getId()).orElseThrow();
        assertThat(unchanged.getDeploymentId()).isEqualTo(oldDeployment.getId());
    }

    private String nativeResponse(int contextLength, int parallel) {
        return nativeResponse("vendor/future-model", "future-instance", contextLength, parallel);
    }

    private String nativeResponse(String key, String instanceId, int contextLength, int parallel) {
        return """
                {"models":[{
                  "type":"llm","key":"%s","display_name":"Future Model","architecture":"future-arch",
                  "quantization":{"name":"Q6_K"},"max_context_length":131072,
                  "loaded_instances":[{"id":"%s","config":{"context_length":%d,"parallel":%d}}],
                  "capabilities":{"vision":true,"trained_for_tool_use":true}
                }]}
                """.formatted(key, instanceId, contextLength, parallel);
    }

    private String nativeResponseWithTwoLoadedModels() {
        return """
                {"models":[
                  {"type":"llm","key":"vendor/new-one","display_name":"New One","architecture":"future-arch",
                   "quantization":{"name":"Q4_K_M"},"max_context_length":131072,
                   "loaded_instances":[{"id":"new-one-instance","config":{"context_length":32768,"parallel":2}}],
                   "capabilities":{"vision":true}},
                  {"type":"llm","key":"vendor/new-two","display_name":"New Two","architecture":"future-arch",
                   "quantization":{"name":"Q8_0"},"max_context_length":131072,
                   "loaded_instances":[{"id":"new-two-instance","config":{"context_length":32768,"parallel":2}}],
                   "capabilities":{"vision":true}}
                ]}
                """;
    }
}
