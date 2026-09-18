package com.aiconnect.llmgateway.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeUrlTest {
    @Test
    void openAiResourceAcceptsRootAndVersionedBases() {
        assertEquals("http://node:8080/v1/models", RuntimeUrl.openAi("http://node:8080", "/models"));
        assertEquals("http://node:8080/v1/models", RuntimeUrl.openAi("http://node:8080/v1/", "models"));
    }

    @Test
    void appendRemovesDuplicateTrailingSlash() {
        assertEquals("http://node:11434/api/tags", RuntimeUrl.append("http://node:11434/", "/api/tags"));
    }
}