package com.aiconnect.llmgateway.dataprotection;

import java.util.List;

/** Immutable policy after platform, organization, project, service and key scopes are merged. */
public record EffectiveDataProtectionPolicy(
        DataProtectionMode mode,
        DataProtectionLevel level,
        DataProtectionAction externalAction,
        boolean allowExternalFailover,
        boolean detectSecrets,
        boolean detectPii,
        boolean detectFinancial,
        boolean detectConfidential,
        boolean detectMedia,
        List<String> customPatterns,
        List<String> appliedScopes
) {
    public static EffectiveDataProtectionPolicy defaults() {
        return new EffectiveDataProtectionPolicy(DataProtectionMode.OFF, DataProtectionLevel.RELAXED,
                DataProtectionAction.ALLOW, true, true, false, false, false, true, List.of(), List.of());
    }

    public boolean enforced() { return mode == DataProtectionMode.ENFORCE; }
}
