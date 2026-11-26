package com.aiconnect.llmgateway.web;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Bounded UTF-8 line reader for SSE streams so a malfunctioning or malicious upstream cannot trigger
 * unbounded heap allocation by streaming bytes without line breaks.
 */
public final class BoundedLineReader implements Closeable {
    public static final int DEFAULT_MAX_LINE_CHARS = 2 * 1024 * 1024;
    private final Reader reader;
    private final int maxLineChars;
    private boolean skipNextLf;

    public BoundedLineReader(InputStream source) {
        this(source, DEFAULT_MAX_LINE_CHARS);
    }

    public BoundedLineReader(InputStream source, int maxLineChars) {
        this.reader = new InputStreamReader(source, StandardCharsets.UTF_8);
        this.maxLineChars = maxLineChars > 0 ? maxLineChars : DEFAULT_MAX_LINE_CHARS;
    }

    public String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch = reader.read();
        if (ch == -1) return null;
        if (skipNextLf && ch == '\n') {
            skipNextLf = false;
            ch = reader.read();
            if (ch == -1) return null;
        }
        skipNextLf = false;
        while (ch != -1) {
            if (ch == '\n') break;
            if (ch == '\r') {
                skipNextLf = true;
                break;
            }
            if (sb.length() >= maxLineChars) {
                throw new IOException("SSE line exceeds maximum permitted length of " + maxLineChars + " chars.");
            }
            sb.append((char) ch);
            ch = reader.read();
        }
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
