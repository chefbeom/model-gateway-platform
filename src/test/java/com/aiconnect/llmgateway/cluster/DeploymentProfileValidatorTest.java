package com.aiconnect.llmgateway.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentProfileValidatorTest {
    @Test
    void standaloneRequiresLocalState() {
        DeploymentProfileProperties properties = new DeploymentProfileProperties();
        properties.setProfile(DeploymentProfile.STANDALONE);
        properties.setSharedStateProvider(SharedStateProvider.REDIS);
        assertThrows(IllegalStateException.class,
                () -> new DeploymentProfileValidator(properties).afterSingletonsInstantiated());
    }

    @Test
    void haAndKubernetesRequireRedis() {
        for (DeploymentProfile profile : new DeploymentProfile[]{DeploymentProfile.HA, DeploymentProfile.KUBERNETES}) {
            DeploymentProfileProperties invalid = new DeploymentProfileProperties();
            invalid.setProfile(profile);
            invalid.setSharedStateProvider(SharedStateProvider.LOCAL);
            assertThrows(IllegalStateException.class,
                    () -> new DeploymentProfileValidator(invalid).afterSingletonsInstantiated());

            DeploymentProfileProperties valid = new DeploymentProfileProperties();
            valid.setProfile(profile);
            valid.setSharedStateProvider(SharedStateProvider.REDIS);
            assertDoesNotThrow(() -> new DeploymentProfileValidator(valid).afterSingletonsInstantiated());
        }
    }

    @Test
    void instanceIdMustNotBeBlank() {
        DeploymentProfileProperties properties = new DeploymentProfileProperties();
        properties.setInstanceId(" ");
        assertThrows(IllegalStateException.class,
                () -> new DeploymentProfileValidator(properties).afterSingletonsInstantiated());
    }
}
