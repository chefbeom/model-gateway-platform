package com.aiconnect.llmgateway.admin;

public record DiscoveredRuntimeModel(
        String providerModelId,
        String compatibilityKey,
        String displayName,
        String modelFamily,
        String quantization,
        Integer contextLength,
        boolean loaded,
        int maxConcurrency,
        String capabilitiesJson,
        String metadataJson
) { }
