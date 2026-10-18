package com.aiconnect.llmgateway.health;

import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.admin.LmStudioModelDiscovery;
import com.aiconnect.llmgateway.cluster.LocalClusterTaskCoordinator;
import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.notification.NotificationService;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeReadinessTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEndpointRepository endpoints = mock(RuntimeEndpointRepository.class);
    private final ModelDeploymentRepository deployments = mock(ModelDeploymentRepository.class);
    private final InferenceRuntimeClient client = mock(InferenceRuntimeClient.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO, "http://runtime:1234", null);
    private ControlPlaneService control;
    private RuntimeHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(endpoint, "id", UUID.randomUUID());
        when(endpoints.findById(endpoint.getId())).thenReturn(Optional.of(endpoint));
        when(endpoints.findByEnabledTrue()).thenReturn(List.of(endpoint));
        when(deployments.findByRuntimeEndpointId(endpoint.getId())).thenReturn(List.of());
        control = new ControlPlaneService(mock(OrganizationRepository.class), mock(ProjectRepository.class),
                mock(InferenceNodeRepository.class), endpoints, deployments, mock(ExternalProviderRepository.class),
                mock(LlmServiceRepository.class), mock(ProjectServiceAccessRepository.class), mock(ServiceTargetRepository.class),
                mock(TeamRepository.class), mock(SecretCipher.class), client, new LmStudioModelDiscovery(mapper));
        monitor = new RuntimeHealthMonitor(endpoints, incidents, control,
                new RecoveryWarmupService(deployments, client, mapper), notifications,
                new LocalClusterTaskCoordinator(), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    @Test
    void recoveredApiWithNoLoadedModelBecomesStandbyWithoutWarmupAndClosesIncident() throws Exception {
        endpoint.failRecovery();
        Incident incident = new Incident(endpoint.getId(), "Previously unreachable");
        when(incidents.findFirstByRuntimeEndpointIdAndStatusOrderByOpenedAtDesc(endpoint.getId(), "OPEN"))
                .thenReturn(Optional.of(incident));
        inventory("{\"models\":[{\"key\":\"gemma\",\"loaded_instances\":[]}]}");
        monitor.checkEndpoints();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.STANDBY);
        assertThat(endpoint.getConsecutiveFailures()).isZero();
        assertThat(endpoint.getLastSuccessAt()).isNotNull();
        verify(client, never()).chatCompletion(any(), any());
        verify(notifications).incidentRecovered(incident, endpoint);
        verify(incidents, never()).save(any());
    }

    @Test
    void emptyCatalogIsStandbyAndSynchronizationKeepsHistoricalModelsAndTargets() throws Exception {
        ModelDeployment historical = new ModelDeployment(endpoint.getId(), "old-model", "Old model", null, null, 8192, true, 1, "[]");
        when(deployments.findByRuntimeEndpointId(endpoint.getId())).thenReturn(List.of(historical));
        inventory("{\"models\":[]}");
        assertThat(control.syncModels(endpoint.getId())).isEmpty();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.STANDBY);
        assertThat(historical.isLoaded()).isFalse();
        verify(deployments, never()).delete(any());
        verify(deployments, never()).deleteAll(anyIterable());
    }

    @Test
    void loadedUnregisteredModelCanRecoverUsingFreshInventory() throws Exception {
        endpoint.failRecovery();
        inventory("{\"models\":[{\"type\":\"llm\",\"key\":\"gemma\",\"loaded_instances\":[{\"id\":\"live-gemma\"}]}]}");
        when(client.chatCompletion(eq(endpoint), any())).thenAnswer(invocation -> {
            assertThat(invocation.<com.fasterxml.jackson.databind.JsonNode>getArgument(1).path("model").asText()).isEqualTo("live-gemma");
            return new RuntimeResult(200, mapper.createObjectNode());
        });
        monitor.checkEndpoints();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
        verify(client).chatCompletion(eq(endpoint), any());
    }

    @Test
    void unloadingLastModelMovesHealthyRuntimeToStandby() throws Exception {
        endpoint.recordHealth(true);
        inventory("{\"models\":[{\"key\":\"gemma\",\"loaded_instances\":[]}]}");
        var result = control.probe(endpoint.getId());
        assertThat(result.reachable()).isTrue();
        assertThat(result.modelIds()).containsExactly("gemma");
        assertThat(result.loadedModelIds()).isEmpty();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.STANDBY);
    }

    @Test
    void unknownJitCatalogDoesNotPretendModelsAreLoadedOrDefinitelyUnloaded() throws Exception {
        inventory("{\"data\":[{\"id\":\"downloaded-gemma\"}]}");
        assertThat(control.probe(endpoint.getId()).reachable()).isTrue();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.CONNECTED);
        verify(client, never()).chatCompletion(any(), any());
    }

    @Test
    void routerLoadingStateIsNotAConnectionFailure() throws Exception {
        ReflectionTestUtils.setField(endpoint, "runtimeType", RuntimeType.LLAMA_CPP);
        endpoint.failRecovery();
        inventory("{\"models\":[{\"id\":\"gemma\",\"status\":{\"value\":\"loading\"}}]}");
        monitor.checkEndpoints();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.LOADING);
        assertThat(endpoint.getConsecutiveFailures()).isZero();
        verify(client, never()).chatCompletion(any(), any());
    }

    @Test
    void llamaCppNativeLoading503IsReachableButOther503ResponsesAreFailures() throws Exception {
        ReflectionTestUtils.setField(endpoint, "runtimeType", RuntimeType.LLAMA_CPP);
        when(client.listModels(endpoint)).thenReturn(new RuntimeResult(503, mapper.readTree("{\"error\":{\"message\":\"Loading model\"}}")));
        assertThat(control.probe(endpoint.getId()).reachable()).isTrue();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.LOADING);
        when(client.listModels(endpoint)).thenReturn(new RuntimeResult(503, mapper.readTree("{\"error\":{\"message\":\"Backend failed\"}}")));
        assertThat(control.probe(endpoint.getId()).reachable()).isFalse();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.SUSPECT);
    }

    @Test
    void disconnectedOrUnauthorizedRuntimeStillBecomesUnhealthy() throws Exception {
        when(client.listModels(endpoint)).thenThrow(new RuntimeUnavailableException("Connection refused", null));
        for (int index = 0; index < 3; index++) assertThat(control.probe(endpoint.getId()).reachable()).isFalse();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.UNHEALTHY);
        doReturn(new RuntimeResult(401, mapper.createObjectNode())).when(client).listModels(endpoint);
        assertThat(control.probe(endpoint.getId()).reachable()).isFalse();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.UNHEALTHY);
    }

    @Test
    void embeddingOnlyRecoveryDoesNotSendAnInvalidChatWarmup() throws Exception {
        endpoint.failRecovery();
        inventory("{\"models\":[{\"key\":\"embedding\",\"type\":\"embedding\",\"loaded_instances\":[{\"id\":\"embed-live\"}]}]}");
        monitor.checkEndpoints();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
        verify(client, never()).chatCompletion(any(), any());
    }

    @Test
    void standbyDoesNotClearManualDrain() throws Exception {
        endpoint.beginDraining();
        inventory("{\"models\":[]}");
        control.probe(endpoint.getId());
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.DRAINING);
    }

    private void inventory(String body) throws Exception {
        when(client.listModels(endpoint)).thenReturn(new RuntimeResult(200, mapper.readTree(body)));
    }
}
