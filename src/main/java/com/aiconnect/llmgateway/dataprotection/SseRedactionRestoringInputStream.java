package com.aiconnect.llmgateway.dataprotection;

import com.aiconnect.llmgateway.web.BoundedLineReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Buffers minimal suffix characters per choice index across SSE chunks when an outbound
 * surrogate token may span chunk boundaries, restoring original PII values on the fly.
 */
public final class SseRedactionRestoringInputStream extends InputStream {
    private final BoundedLineReader source;
    private final ObjectMapper objectMapper;
    private final DataProtectionRedactor.RedactionVault vault;
    private final Map<Integer, StringBuilder> pendingByChoice = new LinkedHashMap<>();
    private ByteArrayInputStream current = new ByteArrayInputStream(new byte[0]);
    private boolean finished;

    public SseRedactionRestoringInputStream(InputStream source, ObjectMapper objectMapper,
                                            DataProtectionRedactor.RedactionVault vault) {
        this(source, objectMapper, vault, BoundedLineReader.DEFAULT_MAX_LINE_CHARS);
    }

    public SseRedactionRestoringInputStream(InputStream source, ObjectMapper objectMapper,
                                            DataProtectionRedactor.RedactionVault vault, int maxLineChars) {
        this.source = new BoundedLineReader(source, maxLineChars);
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
            String flushed = flushRemainingChoices("");
            if (!flushed.isEmpty()) {
                current = new ByteArrayInputStream(flushed.getBytes(StandardCharsets.UTF_8));
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
            String flushed = flushRemainingChoices("data: [DONE]");
            return flushed.isEmpty() ? line : flushed;
        }
        try {
            JsonNode parsed = objectMapper.readTree(payload);
            if (!(parsed instanceof ObjectNode root)) return line;
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return line;
            boolean modified = false;
            for (int i = 0; i < choices.size(); i++) {
                if (!(choices.get(i) instanceof ObjectNode choice)) continue;
                int choiceIndex = choice.path("index").asInt(i);
                boolean hasFinishReason = choice.hasNonNull("finish_reason")
                        && !choice.path("finish_reason").asText("").isBlank();
                JsonNode delta = choice.path("delta");
                if (delta instanceof ObjectNode deltaObj && deltaObj.path("content").isTextual()) {
                    StringBuilder buf = pendingByChoice.computeIfAbsent(choiceIndex, ignored -> new StringBuilder());
                    buf.append(deltaObj.path("content").asText());
                    String emitted = drainEmittableText(choiceIndex, buf, hasFinishReason);
                    deltaObj.put("content", emitted);
                    modified = true;
                } else if (hasFinishReason && delta instanceof ObjectNode deltaObj) {
                    StringBuilder buf = pendingByChoice.get(choiceIndex);
                    if (buf != null && buf.length() > 0) {
                        deltaObj.put("content", drainEmittableText(choiceIndex, buf, true));
                        modified = true;
                    }
                }
            }
            return modified ? "data: " + objectMapper.writeValueAsString(root) : line;
        } catch (Exception ignored) {
            return line;
        }
    }

    private String flushRemainingChoices(String trailer) {
        StringBuilder events = new StringBuilder();
        for (Map.Entry<Integer, StringBuilder> entry : pendingByChoice.entrySet()) {
            StringBuilder buf = entry.getValue();
            if (buf.length() == 0) continue;
            String tail = vault.restoreText(buf.toString());
            buf.setLength(0);
            try {
                events.append("data: ")
                        .append(objectMapper.writeValueAsString(buildFlushChunk(entry.getKey(), tail)))
                        .append("\n\n");
            } catch (Exception ignored) { }
        }
        pendingByChoice.clear();
        if (events.length() == 0) return "";
        if (!trailer.isEmpty()) {
            events.append(trailer);
        }
        return events.toString();
    }

    private String drainEmittableText(int choiceIndex, StringBuilder pending, boolean flushAll) {
        if (pending.isEmpty()) return "";
        String combined = pending.toString();
        if (flushAll || vault.isEmpty()) {
            pending.setLength(0);
            if (flushAll) pendingByChoice.remove(choiceIndex);
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

    private ObjectNode buildFlushChunk(int choiceIndex, String content) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", choiceIndex);
        choice.putObject("delta").put("content", content);
        return root;
    }
}
