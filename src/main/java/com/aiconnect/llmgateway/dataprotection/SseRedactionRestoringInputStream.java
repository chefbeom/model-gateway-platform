package com.aiconnect.llmgateway.dataprotection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Buffers minimal suffix characters across SSE chunks when an outbound
 * surrogate token may span chunk boundaries, restoring original PII values on the fly.
 */
public final class SseRedactionRestoringInputStream extends InputStream {
    private final BufferedReader source;
    private final ObjectMapper objectMapper;
    private final DataProtectionRedactor.RedactionVault vault;
    private final StringBuilder pending = new StringBuilder();
    private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);
    private boolean finished;

    public SseRedactionRestoringInputStream(InputStream source, ObjectMapper objectMapper,
                                            DataProtectionRedactor.RedactionVault vault) {
        this.source = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8));
        this.objectMapper = objectMapper;
        this.vault = vault;
    }

    @Override
    public int read() throws IOException {
        while (current.available() == 0 && !finished) {
            fill();
        }
        return current.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int first = read();
        if (first < 0) return -1;
        bytes[offset] = (byte) first;
        int remaining = Math.min(length - 1, current.available());
        if (remaining > 0) {
            current.read(bytes, offset + 1, remaining);
        }
        return remaining + 1;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }

    private void fill() throws IOException {
        String line = source.readLine();
        if (line == null) {
            if (pending.length() > 0) {
                String tail = vault.restoreText(pending.toString());
                pending.setLength(0);
                String flush = "data: " + objectMapper.writeValueAsString(buildFlushChunk(tail)) + "\n\n";
                current = new ByteArrayInputStream(flush.getBytes(StandardCharsets.UTF_8));
                return;
            }
            finished = true;
            return;
        }
        current = new ByteArrayInputStream((rewriteLine(line) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String rewriteLine(String line) {
        if (!line.startsWith("data:")) return line;
        String payload = line.substring(5).trim();
        if (payload.isEmpty()) return line;
        if ("[DONE]".equals(payload)) {
            if (pending.length() > 0) {
                String tail = vault.restoreText(pending.toString());
                pending.setLength(0);
                try {
                    return "data: " + objectMapper.writeValueAsString(buildFlushChunk(tail)) + "\n\ndata: [DONE]";
                } catch (Exception ignored) { }
            }
            return line;
        }
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            if (!(parsed instanceof ObjectNode root)) return line;
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return line;
            boolean modified = false;
            for (int i = 0; i < choices.size(); i++) {
                if (!(choices.get(i) instanceof ObjectNode choice)) continue;
                boolean hasFinishReason = choice.hasNonNull("finish_reason")
                        && !choice.path("finish_reason").asText("").isBlank();
                JsonNode delta = choice.path("delta");
                if (delta instanceof ObjectNode deltaObj && deltaObj.path("content").isTextual()) {
                    pending.append(deltaObj.path("content").asText());
                    String emitted = drainEmittableText(hasFinishReason);
                    deltaObj.put("content", emitted);
                    modified = true;
                } else if (hasFinishReason && pending.length() > 0 && delta instanceof ObjectNode deltaObj) {
                    deltaObj.put("content", drainEmittableText(true));
                    modified = true;
                }
            }
            return modified ? "data: " + objectMapper.writeValueAsString(root) : line;
        } catch (Exception ignored) {
            return line;
        }
    }

    private String drainEmittableText(boolean flushAll) {
        if (pending.isEmpty()) return "";
        String combined = pending.toString();
        if (flushAll || vault.isEmpty()) {
            pending.setLength(0);
            return vault.restoreText(combined);
        }
        String restored = vault.restoreText(combined);
        int hold = vault.longestPrefixTailLength(restored);
        int emitLen = restored.length() - hold;
        String emitted = restored.substring(0, emitLen);
        pending.setLength(0);
        if (hold > 0) {
            pending.append(restored.substring(emitLen));
        }
        return emitted;
    }

    private ObjectNode buildFlushChunk(String content) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("delta").put("content", content);
        return root;
    }
}
