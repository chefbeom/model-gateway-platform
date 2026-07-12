package com.aiconnect.llmgateway.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "aiconnect.deployment")
public class DeploymentProfileProperties {
    private DeploymentProfile profile = DeploymentProfile.STANDALONE;
    private SharedStateProvider sharedStateProvider = SharedStateProvider.LOCAL;
    private String instanceId = "standalone-1";
    private Duration lockTtl = Duration.ofMinutes(2);
    private Duration activeRequestCounterTtl = Duration.ofHours(24);

    public DeploymentProfile getProfile() { return profile; }
    public void setProfile(DeploymentProfile profile) { this.profile = profile; }
    public SharedStateProvider getSharedStateProvider() { return sharedStateProvider; }
    public void setSharedStateProvider(SharedStateProvider sharedStateProvider) { this.sharedStateProvider = sharedStateProvider; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public Duration getLockTtl() { return lockTtl; }
    public void setLockTtl(Duration lockTtl) { this.lockTtl = lockTtl; }
    public Duration getActiveRequestCounterTtl() { return activeRequestCounterTtl; }
    public void setActiveRequestCounterTtl(Duration activeRequestCounterTtl) { this.activeRequestCounterTtl = activeRequestCounterTtl; }
}
