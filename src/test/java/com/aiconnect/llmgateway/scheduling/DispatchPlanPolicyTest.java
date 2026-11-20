package com.aiconnect.llmgateway.scheduling;

import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.routing.ResolvedTarget;
import com.aiconnect.llmgateway.routing.RoutingDecision;
import com.aiconnect.llmgateway.routing.RoutingService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DispatchPlanPolicyTest {
    @Test void sameDeploymentCannotSwitchFromInternalToExternalAfterReservation() {
        UUID endpointId = UUID.randomUUID();
        var endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO, "http://localhost:1234", null);
        var model = new ModelDeployment(endpointId, "model", "model", "model", null, null, 8192, true, 1, "[]");
        ReflectionTestUtils.setField(model, "id", UUID.randomUUID());
        var internal = new ResolvedTarget(null, model, endpoint, null, 1, "LOCAL");
        var external = new ResolvedTarget(null, model, endpoint, null, 1, "MANUAL_EXTERNAL");
        var plan = DispatchPlan.immediate(mock(RoutingService.class), decision(internal));
        plan.revalidateBeforeAttempt(() -> decision(external));
        assertThat(plan.acquire(internal)).isNull();
        plan.close();
    }
    private RoutingDecision decision(ResolvedTarget target) {
        return new RoutingDecision(List.of(target), Set.of(), false, "COMPATIBLE", "SAFE", List.of());
    }
}
