package com.aiconnect.llmgateway.gateway;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

final class StreamingResponsePrefetcher {
    private StreamingResponsePrefetcher() { }

    static InputStream requireFirstByte(InputStream source) throws IOException {
        int first = source.read();
        if (first < 0) throw new EOFException("The runtime closed the stream before its first response byte.");
        return new SequenceInputStream(new ByteArrayInputStream(new byte[] {(byte) first}), source);
    }
}
