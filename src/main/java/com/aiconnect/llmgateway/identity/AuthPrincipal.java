package com.aiconnect.llmgateway.identity;

import java.util.UUID;

public record AuthPrincipal(UUID userId, String email, boolean platformAdmin) { }
