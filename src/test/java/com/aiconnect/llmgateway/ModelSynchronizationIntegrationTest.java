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

import java.util.List;
import java.util.UUID;

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
    @Autowired ProjectRepository projects;
    @Autowired LlmRequestRepository requests;
    @Autowired LlmRequestAttemptRepository attempts;
    @Autowired PlaygroundRequestRepository playgroundRequests;
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
        assertThat(deployments.findById(oldDeployment.getId())).isEmpty();
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

    @Test
    void rekeysLlamaCppPathAliasesWithoutChangingTargetDeploymentIdentity() throws Exception {
        Organization organization = organizations.save(new Organization("llama alias rekey org"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "llama-alias-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LLAMA_CPP,
                "http://llama-alias-node:4040", null));
        LlmService service = services.save(new LlmService(organization.getId(), "llama-alias-service", "llama alias service",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));

        String pathId = "/opt/llm/models/gemma-4-12b-it-qat-q4_0.gguf";
        when(runtimeClient.listModels(any(RuntimeEndpoint.class))).thenReturn(new RuntimeResult(200, objectMapper.readTree("""
                {"models":[{"name":"/opt/llm/models/gemma-4-12b-it-qat-q4_0.gguf","model":"/opt/llm/models/gemma-4-12b-it-qat-q4_0.gguf","status":{"value":"unloaded"}}]}
                """)));
        controlPlane.syncModels(endpoint.getId());
        ModelDeployment legacy = deployments.findByRuntimeEndpointId(endpoint.getId()).get(0);
        assertThat(legacy.getProviderModelId()).isEqualTo(pathId);
        ServiceTarget target = targets.save(new ServiceTarget(service.getId(), legacy.getId(), 1, 100, false, null, false));

        String alias = "gemma-4-12b-it-qat-q4_0";
        when(runtimeClient.listModels(any(RuntimeEndpoint.class))).thenReturn(new RuntimeResult(200, objectMapper.readTree("""
                {"models":[{"name":"/opt/llm/models/gemma-4-12b-it-qat-q4_0.gguf","model":"/opt/llm/models/gemma-4-12b-it-qat-q4_0.gguf","status":{"value":"loaded"}}],
                 "data":[{"id":"gemma-4-12b-it-qat-q4_0","object":"model"}]}
                """)));
        controlPlane.syncModels(endpoint.getId());

        List<ModelDeployment> current = deployments.findByRuntimeEndpointId(endpoint.getId());
        assertThat(current).hasSize(1);
        assertThat(current.get(0).getId()).isEqualTo(legacy.getId());
        assertThat(current.get(0).getProviderModelId()).isEqualTo(alias);
        assertThat(current.get(0).getCompatibilityKey()).isEqualTo(alias);
        assertThat(current.get(0).isLoaded()).isTrue();
        assertThat(targets.findById(target.getId()).orElseThrow().getDeploymentId()).isEqualTo(legacy.getId());
    }

    @Test
    void prunesOnlyUnreferencedStaleDiscoveredRowsAndKeepsAuditHistory() throws Exception {
        Organization organization = organizations.save(new Organization("stale model retention org"));
        Project project = projects.save(new Project(organization.getId(), "stale model retention project"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "stale-retention-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), RuntimeType.LLAMA_CPP,
                "http://stale-retention-node:4040", null));
        LlmService service = services.save(new LlmService(organization.getId(), "stale-retention-service", "stale retention service",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));

        when(runtimeClient.listModels(any(RuntimeEndpoint.class))).thenReturn(new RuntimeResult(200, objectMapper.readTree("""
                {"models":[
                  {"name":"retained-model.gguf","model":"/opt/llm/models/retained-model.gguf","status":{"value":"loaded"}},
                  {"name":"orphan-model.gguf","model":"/opt/llm/models/orphan-model.gguf","status":{"value":"unloaded"}}
                ]}
                """)));
        controlPlane.syncModels(endpoint.getId());
        List<ModelDeployment> initial = deployments.findByRuntimeEndpointId(endpoint.getId());
        ModelDeployment retained = initial.stream().filter(item -> item.getProviderModelId().equals("retained-model.gguf")).findFirst().orElseThrow();
        ModelDeployment orphan = initial.stream().filter(item -> item.getProviderModelId().equals("orphan-model.gguf")).findFirst().orElseThrow();
        ServiceTarget target = targets.save(new ServiceTarget(service.getId(), retained.getId(), 1, 100, false, null, false));
        LlmRequest request = new LlmRequest("stale-history-" + UUID.randomUUID(), project.getId(), null, service, false);
        request.succeed(retained.getId(), 1, 1, 1, 200, 0);
        request = requests.save(request);
        attempts.save(new LlmRequestAttempt(request.getId(), retained.getId(), 1));
        playgroundRequests.save(new PlaygroundRequest("stale-playground-" + UUID.randomUUID(), organization.getId(),
                retained.getId(), "RUNTIME", "stale runtime", retained.getProviderModelId(), endpoint.getBaseUrl(), false, null));

        when(runtimeClient.listModels(any(RuntimeEndpoint.class))).thenReturn(new RuntimeResult(200,
                objectMapper.readTree("{\"models\":[],\"data\":[]}")));
        controlPlane.syncModels(endpoint.getId());

        assertThat(deployments.findById(retained.getId())).isPresent()
                .get().extracting(ModelDeployment::getHealthStatus).isEqualTo(HealthStatus.UNHEALTHY);
        assertThat(targets.findById(target.getId()).orElseThrow().getDeploymentId()).isEqualTo(retained.getId());
        assertThat(requests.existsByFinalDeploymentIdIn(List.of(retained.getId()))).isTrue();
        assertThat(attempts.existsByDeploymentIdIn(List.of(retained.getId()))).isTrue();
        assertThat(playgroundRequests.existsByDeploymentIdIn(List.of(retained.getId()))).isTrue();
        assertThat(deployments.findById(orphan.getId())).isEmpty();
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
