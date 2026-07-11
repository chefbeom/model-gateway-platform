package com.aiconnect.llmgateway.routing;

import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.ServiceTarget;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedTargetSelectorTest {
    @Test
    void distributesIdleSelectionsAccordingToConfiguredWeight() {
        ActiveRequestRegistry active = new ActiveRequestRegistry();
        WeightedTargetSelector selector = new WeightedTargetSelector(active);
        ResolvedTarget heavy = target(3);
        ResolvedTarget light = target(1);
        int heavySelections = 0;
        for (int index = 0; index < 40; index++) if (selector.order(List.of(heavy, light)).get(0) == heavy) heavySelections++;

        assertThat(heavySelections).isBetween(29, 31);
    }
    private ResolvedTarget target(int weight) {
        ModelDeployment deployment = new ModelDeployment(UUID.randomUUID(), "model-" + UUID.randomUUID(), "model", null, null, 8192, true, 8, "[]");
        ServiceTarget target = new ServiceTarget(UUID.randomUUID(), UUID.randomUUID(), 1, weight, false, null);
        ReflectionTestUtils.setField(deployment, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(target, "id", UUID.randomUUID());
        return new ResolvedTarget(target, deployment, null, 8);
    }
}
