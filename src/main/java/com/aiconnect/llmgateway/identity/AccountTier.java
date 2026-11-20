package com.aiconnect.llmgateway.identity;

public enum AccountTier {
    SYSTEM_ADMIN,
    PROJECT_DIRECTOR,
    GENERAL_USER;

    public static AccountTier effectiveFor(AppUser user) {
        if (user == null) return GENERAL_USER;
        if (user.isPlatformAdmin()) return SYSTEM_ADMIN;
        return user.getAccountTier() != null ? user.getAccountTier() : GENERAL_USER;
    }
}
