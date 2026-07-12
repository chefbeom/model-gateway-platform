package com.aiconnect.llmgateway.identity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthProperties {
    private final String signingKey;
    private final String refreshPepper;
    private final long accessTokenSeconds;
    private final long refreshTokenSeconds;
    private final boolean cookieSecure;
    @Autowired
    public AuthProperties(@Value("${AUTH_SIGNING_KEY:development-only-auth-signing-key-change-before-production}") String signingKey,
                          @Value("${AUTH_REFRESH_PEPPER:development-only-refresh-pepper-change-before-production}") String refreshPepper,
                          @Value("${AUTH_ACCESS_TOKEN_SECONDS:900}") long accessTokenSeconds,
                          @Value("${AUTH_REFRESH_TOKEN_SECONDS:2592000}") long refreshTokenSeconds,
                          @Value("${AUTH_COOKIE_SECURE:true}") boolean cookieSecure) {
        this.signingKey = signingKey; this.refreshPepper = refreshPepper; this.accessTokenSeconds = accessTokenSeconds; this.refreshTokenSeconds = refreshTokenSeconds;
        this.cookieSecure = cookieSecure;
    }
    public String signingKey() { return signingKey; }
    public String refreshPepper() { return refreshPepper; }
    public AuthProperties(String signingKey, String refreshPepper, long accessTokenSeconds, long refreshTokenSeconds) {
        this(signingKey, refreshPepper, accessTokenSeconds, refreshTokenSeconds, true);
    }
    public long accessTokenSeconds() { return accessTokenSeconds; }
    public long refreshTokenSeconds() { return refreshTokenSeconds; }
    public boolean cookieSecure() { return cookieSecure; }
}
