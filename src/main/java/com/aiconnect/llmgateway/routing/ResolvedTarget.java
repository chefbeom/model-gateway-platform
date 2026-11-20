package com.aiconnect.llmgateway.routing;

import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.ServiceTarget;

public record ResolvedTarget(ServiceTarget target, ModelDeployment deployment, RuntimeEndpoint endpoint,
                             ExternalProvider externalProvider, int maxConcurrency, String routingReason) {
    public ResolvedTarget(ServiceTarget target, ModelDeployment deployment, RuntimeEndpoint endpoint, int maxConcurrency) {
        this(target, deployment, endpoint, null, maxConcurrency, "LOCAL");
    }
    public boolean external() { return externalProvider != null; }
    /** External data boundary is independent of which HTTP client calls the target. */
    public boolean externalDataBoundary() {
        return external() || (endpoint != null && ("MANUAL_EXTERNAL".equals(routingReason) || "AUTO_FAILOVER".equals(routingReason)));
    }
    public String providerType() { return external() ? externalProvider.getProviderType().name() : externalDataBoundary() ? "EXTERNAL_RUNTIME" : "LOCAL"; }
}
