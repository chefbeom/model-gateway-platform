package com.aiconnect.llmgateway.identity;

import java.util.UUID;

public record AuthPrincipal(
        UUID userId,
        String email,
        boolean platformAdmin,
        AccountTier accountTier,
        boolean totpEnabled,
        boolean mfaVerified
) {
    public AuthPrincipal(UUID userId, String email, boolean platformAdmin) {
        this(userId, email, platformAdmin,
                platformAdmin ? AccountTier.SYSTEM_ADMIN : AccountTier.GENERAL_USER, false, false);
    }
}
