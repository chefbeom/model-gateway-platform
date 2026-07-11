package com.aiconnect.llmgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.URI;

@Component
public class RuntimeProxySettings {
    private final InetSocketAddress address;

    public RuntimeProxySettings(@Value("${RUNTIME_HTTP_PROXY_URL:}") String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            this.address = null;
            return;
        }
        try {
            URI uri = URI.create(proxyUrl.trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            this.address = InetSocketAddress.createUnresolved(uri.getHost(), port);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("RUNTIME_HTTP_PROXY_URL must be an HTTP proxy URL such as http://tailscale:1055.", exception);
        }
    }

    public boolean enabled() { return address != null; }
    public InetSocketAddress address() { return address; }
}
