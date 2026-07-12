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
    public String providerType() { return external() ? externalProvider.getProviderType().name() : "LOCAL"; }
}
