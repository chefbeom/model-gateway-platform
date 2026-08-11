package com.aiconnect.llmgateway.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;

public final class BufferedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    public BufferedBodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
    @Override public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { }
        };
    }
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding() == null ? java.nio.charset.StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(getCharacterEncoding()))); }
}
