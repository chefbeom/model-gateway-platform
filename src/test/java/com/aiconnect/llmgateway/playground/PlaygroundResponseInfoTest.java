package com.aiconnect.llmgateway.playground;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaygroundResponseInfoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void jsonExtractsOnlySafeMetadataWithoutRetainingReasoningText() throws Exception {
        var choice = mapper.readTree("""
                {"finish_reason":"length","message":{"content":"","reasoning_content":"private thinking"}}
                """);
        var info = PlaygroundResponseInfo.from(choice, false);
        assertEquals("length", info.finishReason());
        assertTrue(info.reasoningPresent());
        assertFalse(info.toString().contains("private thinking"));
    }

    @Test void streamingMergesMetadataAcrossChunks() throws Exception {
        var first = PlaygroundResponseInfo.from(mapper.readTree("""
                {"delta":{"reasoning_content":"private thinking"},"finish_reason":null}
                """), true);
        var second = PlaygroundResponseInfo.from(mapper.readTree("""
                {"delta":{"content":"partial"},"finish_reason":"length"}
                """), true);
        var combined = first.merge(second);
        assertTrue(combined.reasoningPresent());
        assertEquals("length", combined.finishReason());
        assertFalse(combined.toString().contains("private thinking"));
    }

    @Test void missingOrUnsafeMetadataIsNotInventedOrStored() throws Exception {
        var info = PlaygroundResponseInfo.from(mapper.readTree("""
                {"finish_reason":"arbitrary private content!","message":{"reasoning_content":""}}
                """), false);
        assertNull(info.finishReason());
        assertFalse(info.reasoningPresent());
        assertNull(PlaygroundResponseInfo.from(mapper.missingNode(), true).finishReason());
    }
}
