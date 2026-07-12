package com.aiconnect.llmgateway.routing;

import java.util.UUID;

public interface WeightedSelectionStore {
    long count(UUID targetId);
    void increment(UUID targetId);
}
