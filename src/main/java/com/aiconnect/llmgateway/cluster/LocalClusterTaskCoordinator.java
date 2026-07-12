package com.aiconnect.llmgateway.cluster;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "aiconnect.deployment.shared-state-provider", havingValue = "LOCAL", matchIfMissing = true)
public class LocalClusterTaskCoordinator implements ClusterTaskCoordinator {
    @Override
    public boolean runIfLeader(String taskName, Runnable task) {
        task.run();
        return true;
    }
}
