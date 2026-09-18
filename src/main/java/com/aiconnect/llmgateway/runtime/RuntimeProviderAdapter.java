package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.fasterxml.jackson.databind.JsonNode;

/** Provider-specific contract used by the local runtime router. */
public interface RuntimeProviderAdapter {
    boolean supports(RuntimeType runtimeType);
    RuntimeResult listModels(RuntimeEndpoint endpoint);
    RuntimeResult chatCompletion(RuntimeEndpoint endpoint, JsonNode request);
}