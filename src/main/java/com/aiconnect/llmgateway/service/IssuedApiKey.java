package com.aiconnect.llmgateway.service;

import java.time.Instant;
import java.util.UUID;

public record IssuedApiKey(UUID id, String name, String keyPrefix, String secret, Instant expiresAt) { }
