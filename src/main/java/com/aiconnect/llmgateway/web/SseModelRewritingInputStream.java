package com.aiconnect.llmgateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class SseModelRewritingInputStream extends InputStream {
    private final BufferedReader source;
    private final ObjectMapper objectMapper;
    private final String logicalModel;
    private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);
    private boolean finished;
    SseModelRewritingInputStream(InputStream source, ObjectMapper objectMapper, String logicalModel) {
        this.source = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8)); this.objectMapper = objectMapper; this.logicalModel = logicalModel;
    }
    @Override public int read() throws IOException {
        while (current.available() == 0 && !finished) fill();
        return current.read();
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        int first = read();
        if (first < 0) return -1;
        bytes[offset] = (byte) first;
        int remaining = Math.min(length - 1, current.available());
        if (remaining > 0) current.read(bytes, offset + 1, remaining);
        return remaining + 1;
    }
    @Override public void close() throws IOException { source.close(); }
    private void fill() throws IOException {
        String line = source.readLine();
        if (line == null) { finished = true; return; }
        current = new ByteArrayInputStream((rewrite(line) + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private String rewrite(String line) {
        if (!line.startsWith("data:")) return line;
        String payload = line.substring(5).trim();
        if (payload.equals("[DONE]") || payload.isEmpty()) return line;
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            if (parsed instanceof ObjectNode object) { object.put("model", logicalModel); return "data: " + objectMapper.writeValueAsString(object); }
        } catch (Exception ignored) { }
        return line;
    }
}
