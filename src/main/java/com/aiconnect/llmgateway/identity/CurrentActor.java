package com.aiconnect.llmgateway.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;
import java.util.UUID;

public final class CurrentActor {
    private CurrentActor() { }
    public static Optional<AuthPrincipal> principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal ? Optional.of(principal) : Optional.empty();
    }
    public static UUID userIdOrNull() { return principal().map(AuthPrincipal::userId).orElse(null); }
}
