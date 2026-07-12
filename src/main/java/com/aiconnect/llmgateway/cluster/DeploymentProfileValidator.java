package com.aiconnect.llmgateway.cluster;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DeploymentProfileValidator implements SmartInitializingSingleton {
    private final DeploymentProfileProperties properties;

    public DeploymentProfileValidator(DeploymentProfileProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (properties.getProfile() != DeploymentProfile.STANDALONE
                && properties.getSharedStateProvider() != SharedStateProvider.REDIS) {
            throw new IllegalStateException(properties.getProfile() + " requires AICONNECT_SHARED_STATE_PROVIDER=REDIS");
        }
        if (properties.getProfile() == DeploymentProfile.STANDALONE
                && properties.getSharedStateProvider() != SharedStateProvider.LOCAL) {
            throw new IllegalStateException("STANDALONE requires AICONNECT_SHARED_STATE_PROVIDER=LOCAL");
        }
        if (!StringUtils.hasText(properties.getInstanceId())) {
            throw new IllegalStateException("AICONNECT_INSTANCE_ID must not be blank");
        }
    }
}
