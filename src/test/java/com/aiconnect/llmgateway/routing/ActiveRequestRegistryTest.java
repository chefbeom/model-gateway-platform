package com.aiconnect.llmgateway.routing;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveRequestRegistryTest {
    @Test
    void enforcesConfiguredConcurrencyAndReleasesCapacity() {
        ActiveRequestRegistry registry = new ActiveRequestRegistry();
        UUID deployment = UUID.randomUUID();

        assertThat(registry.tryAcquire(deployment, 1)).isTrue();
        assertThat(registry.tryAcquire(deployment, 1)).isFalse();
        registry.release(deployment);
        assertThat(registry.tryAcquire(deployment, 1)).isTrue();
    }
}
