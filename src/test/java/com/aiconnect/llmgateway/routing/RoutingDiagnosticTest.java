package com.aiconnect.llmgateway.routing;

import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.HealthStatus;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.domain.ServiceTarget;
import com.aiconnect.llmgateway.domain.RetryPolicy;
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

class RoutingDiagnosticTest {
    private final ServiceTargetRepository targets = mock(ServiceTargetRepository.class);
    private final ModelDeploymentRepository deployments = mock(ModelDeploymentRepository.class);
    private final RuntimeEndpointRepository endpoints = mock(RuntimeEndpointRepository.class);
    private final ActiveRequestRegistry active = new ActiveRequestRegistry();
    private final RoutingService routing = new RoutingService(targets, deployments, endpoints, active,
            new WeightedTargetSelector(active), new ObjectMapper());

    @Test
    void evaluateRetainsWhyConfiguredTargetsWereExcluded() {
        UUID serviceId = UUID.randomUUID();
        LlmService service = new LlmService(UUID.randomUUID(), "vision-service", "Vision", FailoverPolicy.COMPATIBLE,
                RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "id", serviceId);

        RuntimeEndpoint endpoint = endpoint();
        ModelDeployment missingCapability = deployment(endpoint, "text-only", "[]", true, HealthStatus.HEALTHY);
        ModelDeployment unhealthy = deployment(endpoint, "unhealthy", "[\"VISION\"]", true, HealthStatus.UNHEALTHY);
        ServiceTarget first = target(serviceId, missingCapability, false, true);
        ServiceTarget second = target(serviceId, unhealthy, false, true);
        List<ServiceTarget> configured = List.of(first, second);

        when(targets.findByServiceIdOrderByPriorityAsc(serviceId)).thenReturn(configured);
        when(deployments.findById(missingCapability.getId())).thenReturn(Optional.of(missingCapability));
        when(deployments.findById(unhealthy.getId())).thenReturn(Optional.of(unhealthy));
        when(endpoints.findById(endpoint.getId())).thenReturn(Optional.of(endpoint));

        RoutingDecision decision = routing.evaluate(service, Set.of("VISION"), UUID.randomUUID());

        assertThat(decision.eligibleTargets()).isEmpty();
        assertThat(decision.evaluations()).hasSize(2);
        assertThat(decision.evaluations().get(0).reasonCodes()).containsExactly("CAPABILITY_MISSING");
        assertThat(decision.evaluations().get(0).missingCapabilities()).containsExactly("VISION");
        assertThat(decision.evaluations().get(1).reasonCodes()).containsExactly("DEPLOYMENT_UNHEALTHY");
    }

    private RuntimeEndpoint endpoint() {
        RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO,
                "http://127.0.0.1:1234", null);
        ReflectionTestUtils.setField(endpoint, "id", UUID.randomUUID());
        endpoint.recordHealth(true);
        return endpoint;
    }

    private ModelDeployment deployment(RuntimeEndpoint endpoint, String modelId, String capabilities,
                                       boolean loaded, HealthStatus health) {
        ModelDeployment deployment = new ModelDeployment(endpoint.getId(), modelId, modelId, modelId,
                null, null, 8192, loaded, 4, capabilities);
        ReflectionTestUtils.setField(deployment, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(deployment, "healthStatus", health);
        return deployment;
    }

    private ServiceTarget target(UUID serviceId, ModelDeployment deployment, boolean degraded, boolean enabled) {
        ServiceTarget target = new ServiceTarget(serviceId, deployment.getId(), 1, 100, degraded, null);
        ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(target, "enabled", enabled);
        return target;
    }
}
