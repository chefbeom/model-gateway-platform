package com.aiconnect.llmgateway.identity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuthProperties {
    private static final long DEFAULT_PRE_MFA_REFRESH_SECONDS = 600L;

    private final String signingKey;
    private final String refreshPepper;
    private final long accessTokenSeconds;
    private final long refreshTokenSeconds;
    private final boolean cookieSecure;
    private final long preMfaRefreshTokenSeconds;

    @Autowired
    public AuthProperties(@Value("${AUTH_SIGNING_KEY:development-only-auth-signing-key-change-before-production}") String signingKey,
                          @Value("${AUTH_REFRESH_PEPPER:development-only-refresh-pepper-change-before-production}") String refreshPepper,
                          @Value("${AUTH_ACCESS_TOKEN_SECONDS:900}") long accessTokenSeconds,
                          @Value("${AUTH_REFRESH_TOKEN_SECONDS:2592000}") long refreshTokenSeconds,
                          @Value("${AUTH_COOKIE_SECURE:true}") boolean cookieSecure,
                          @Value("${AUTH_PRE_MFA_REFRESH_TOKEN_SECONDS:600}") long preMfaRefreshTokenSeconds) {
        this.signingKey = signingKey;
        this.refreshPepper = refreshPepper;
        this.accessTokenSeconds = accessTokenSeconds;
        this.refreshTokenSeconds = refreshTokenSeconds;
        this.cookieSecure = cookieSecure;
        this.preMfaRefreshTokenSeconds = preMfaRefreshTokenSeconds > 0 ? preMfaRefreshTokenSeconds : DEFAULT_PRE_MFA_REFRESH_SECONDS;
    }

    public AuthProperties(String signingKey, String refreshPepper, long accessTokenSeconds, long refreshTokenSeconds, boolean cookieSecure) {
        this(signingKey, refreshPepper, accessTokenSeconds, refreshTokenSeconds, cookieSecure, DEFAULT_PRE_MFA_REFRESH_SECONDS);
    }

    public AuthProperties(String signingKey, String refreshPepper, long accessTokenSeconds, long refreshTokenSeconds) {
        this(signingKey, refreshPepper, accessTokenSeconds, refreshTokenSeconds, true, DEFAULT_PRE_MFA_REFRESH_SECONDS);
    }

    public String signingKey() { return signingKey; }
    public String refreshPepper() { return refreshPepper; }
    public long accessTokenSeconds() { return accessTokenSeconds; }
    public long refreshTokenSeconds() { return refreshTokenSeconds; }
    public boolean cookieSecure() { return cookieSecure; }
    public long preMfaRefreshTokenSeconds() { return preMfaRefreshTokenSeconds; }
}
