package com.aiconnect.llmgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        String adminToken,
        String apiKeyPepper,
        String encryptionKey,
        long healthCheckDelayMs,
        int connectTimeoutMs,
        int responseTimeoutMs
) { }
