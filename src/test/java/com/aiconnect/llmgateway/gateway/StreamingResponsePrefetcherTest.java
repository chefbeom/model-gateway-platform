package com.aiconnect.llmgateway.gateway;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamingResponsePrefetcherTest {
    @Test
    void preservesThePrefetchedByte() throws Exception {
        byte[] original = "data: first\n\n".getBytes(StandardCharsets.UTF_8);
        assertThat(StreamingResponsePrefetcher.requireFirstByte(new ByteArrayInputStream(original)).readAllBytes())
                .isEqualTo(original);
    }

    @Test
    void rejectsAnEmptySuccessfulResponse() {
        assertThatThrownBy(() -> StreamingResponsePrefetcher.requireFirstByte(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(EOFException.class);
    }
}
