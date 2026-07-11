package com.aiconnect.llmgateway.retention;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class ReplayableBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    ReplayableBodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
    @Override public ServletInputStream getInputStream() {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { }
        };
    }
    @Override public BufferedReader getReader() { Charset charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding()); return new BufferedReader(new InputStreamReader(getInputStream(), charset)); }
}
