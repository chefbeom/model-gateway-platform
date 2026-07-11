package com.aiconnect.llmgateway.routing;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.repository.ServiceTargetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingFailoverPolicyTest {
    private final ServiceTargetRepository targets = mock(ServiceTargetRepository.class);
    private final ModelDeploymentRepository deployments = mock(ModelDeploymentRepository.class);
    private final RuntimeEndpointRepository endpoints = mock(RuntimeEndpointRepository.class);
    private final ActiveRequestRegistry active = new ActiveRequestRegistry();
    private final RoutingService routing = new RoutingService(targets, deployments, endpoints, active,
            new WeightedTargetSelector(active), new ObjectMapper());

    @Test
    void strictOnlyUsesDeploymentsInPrimaryCompatibilityGroup() {
        Fixture fixture = fixture(FailoverPolicy.STRICT, false);
        List<ResolvedTarget> result = routing.candidates(fixture.service(), Set.of());

        assertThat(result).extracting(item -> item.deployment().getProviderModelId())
                .containsExactlyInAnyOrder("primary-physical", "equivalent-physical");
    }

    @Test
    void compatibleAllowsAdminApprovedModelsButNotDegradedTargetsByDefault() {
        Fixture fixture = fixture(FailoverPolicy.COMPATIBLE, false);
        List<ResolvedTarget> result = routing.candidates(fixture.service(), Set.of());

        assertThat(result).extracting(item -> item.deployment().getProviderModelId())
                .containsExactlyInAnyOrder("primary-physical", "equivalent-physical", "compatible-physical");
    }

    @Test
    void degradedPolicyIncludesExplicitlyDegradedTarget() {
        Fixture fixture = fixture(FailoverPolicy.DEGRADED, false);
        List<ResolvedTarget> result = routing.candidates(fixture.service(), Set.of());

        assertThat(result).extracting(item -> item.deployment().getProviderModelId())
                .contains("degraded-physical");
    }

    private Fixture fixture(FailoverPolicy policy, boolean allowDegraded) {
        UUID serviceId = UUID.randomUUID();
        LlmService service = new LlmService(UUID.randomUUID(), "policy-test", "Policy", policy,
                RetryPolicy.SAFE, allowDegraded, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "id", serviceId);

        Entry primary = entry(serviceId, 1, false, "primary-physical", "contract-a");
        Entry equivalent = entry(serviceId, 2, false, "equivalent-physical", "contract-a");
        Entry compatible = entry(serviceId, 3, false, "compatible-physical", "contract-b");
        Entry degraded = entry(serviceId, 4, true, "degraded-physical", "contract-c");
        List<Entry> entries = List.of(primary, equivalent, compatible, degraded);

        when(targets.findByServiceIdAndEnabledTrueOrderByPriorityAsc(serviceId))
                .thenReturn(entries.stream().map(Entry::target).toList());
        for (Entry entry : entries) {
            when(deployments.findById(entry.deployment().getId())).thenReturn(Optional.of(entry.deployment()));
            when(endpoints.findById(entry.endpoint().getId())).thenReturn(Optional.of(entry.endpoint()));
        }
        return new Fixture(service);
    }

    private Entry entry(UUID serviceId, int priority, boolean degraded, String modelId, String compatibilityKey) {
        RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO,
                "http://127.0.0.1:" + (12000 + priority), null);
        endpoint.recordHealth(true);
        ReflectionTestUtils.setField(endpoint, "id", UUID.randomUUID());

        ModelDeployment deployment = new ModelDeployment(endpoint.getId(), modelId, compatibilityKey, modelId,
                null, null, 8192, true, 4, "[]");
        ReflectionTestUtils.setField(deployment, "id", UUID.randomUUID());
        ServiceTarget target = new ServiceTarget(serviceId, deployment.getId(), priority, 100, degraded, null);
        ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
        return new Entry(target, deployment, endpoint);
    }

    private record Fixture(LlmService service) { }
    private record Entry(ServiceTarget target, ModelDeployment deployment, RuntimeEndpoint endpoint) { }
}
