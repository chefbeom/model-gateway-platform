package com.aiconnect.llmgateway.health;

import com.aiconnect.llmgateway.domain.HealthStatus;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEndpointStateTest {
    @Test
    void requiresConsecutiveFailuresBeforeUnhealthyAndWarmupBeforeRecovery() {
        RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO, "http://node:1234", null);
        endpoint.recordHealth(true);
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);

        endpoint.recordHealth(false);
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.SUSPECT);
        assertThat(endpoint.getConsecutiveFailures()).isEqualTo(1);
        endpoint.recordHealth(false);
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.SUSPECT);
        endpoint.recordHealth(false);
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.UNHEALTHY);

        endpoint.recordHealth(true);
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.RECOVERING);
        endpoint.completeRecovery();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(endpoint.getConsecutiveFailures()).isZero();
    }

    @Test
    void drainingIsStickyAndFailedWarmupReturnsToUnhealthy() {
        RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), RuntimeType.LM_STUDIO, "http://node:1234", null);
        endpoint.recordHealth(true);
        endpoint.beginDraining();
        endpoint.recordHealth(true);
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.DRAINING);

        endpoint.beginRecovery();
        endpoint.failRecovery();
        assertThat(endpoint.getHealthStatus()).isEqualTo(HealthStatus.UNHEALTHY);
        assertThat(endpoint.getConsecutiveFailures()).isGreaterThanOrEqualTo(endpoint.getFailureThreshold());
    }
}
