package com.aiconnect.llmgateway.routing;

import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.ServiceTarget;

public record ResolvedTarget(ServiceTarget target, ModelDeployment deployment, RuntimeEndpoint endpoint, int maxConcurrency) { }
