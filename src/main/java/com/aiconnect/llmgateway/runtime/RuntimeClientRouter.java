package com.aiconnect.llmgateway.runtime;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Dispatches local runtime calls without changing the public gateway contract. */
@Component
public class RuntimeClientRouter implements InferenceRuntimeClient {
    private final Map<RuntimeType, RuntimeProviderAdapter> adapters = new EnumMap<>(RuntimeType.class);

    public RuntimeClientRouter(List<RuntimeProviderAdapter> providers) {
        for (RuntimeProviderAdapter provider : providers) {
            for (RuntimeType type : RuntimeType.values()) {
                if (provider.supports(type)) adapters.put(type, provider);
            }
        }
    }

    @Override
    public RuntimeResult listModels(RuntimeEndpoint endpoint) {
        return adapter(endpoint).listModels(endpoint);
    }

    @Override
    public RuntimeResult chatCompletion(RuntimeEndpoint endpoint, JsonNode request) {
        return adapter(endpoint).chatCompletion(endpoint, request);
    }

    private RuntimeProviderAdapter adapter(RuntimeEndpoint endpoint) {
        RuntimeType type = endpoint.getRuntimeType() == null ? RuntimeType.LM_STUDIO : endpoint.getRuntimeType();
        RuntimeProviderAdapter provider = adapters.get(type);
        if (provider == null) throw new RuntimeUnavailableException("No runtime adapter is configured for " + type + ".", null);
        return provider;
    }
}