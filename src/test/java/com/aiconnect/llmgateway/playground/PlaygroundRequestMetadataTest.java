package com.aiconnect.llmgateway.playground;

import com.aiconnect.llmgateway.domain.PlaygroundRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaygroundRequestMetadataTest {
    @Test
    void storesOnlyBoundedRequestModeMetadataForUsageAnalytics() {
        PlaygroundRequest request = new PlaygroundRequest("request-1", UUID.randomUUID(), UUID.randomUUID(),
                "RUNTIME", "local", "model-a", "http://runtime.invalid", true, UUID.randomUUID());

        assertEquals("UNKNOWN", request.getRequestType());
        request.recordRequestMetadata("VISION+STRUCTURED_OUTPUT", "HIGH", "FAST");

        assertEquals("VISION+STRUCTURED_OUTPUT", request.getRequestType());
        assertEquals("HIGH", request.getReasoningEffort());
        assertEquals("FAST", request.getRequestedServiceTier());
    }
}
