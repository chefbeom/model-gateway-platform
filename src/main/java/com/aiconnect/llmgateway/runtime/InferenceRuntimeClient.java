package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.fasterxml.jackson.databind.JsonNode;

public interface InferenceRuntimeClient {
    RuntimeResult listModels(RuntimeEndpoint endpoint);
    RuntimeResult chatCompletion(RuntimeEndpoint endpoint, JsonNode request);
}
