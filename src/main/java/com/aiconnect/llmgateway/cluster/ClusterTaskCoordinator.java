package com.aiconnect.llmgateway.cluster;

public interface ClusterTaskCoordinator {
    boolean runIfLeader(String taskName, Runnable task);
}
