package com.aiconnect.llmgateway.routing;

import java.util.List;
import java.util.Set;

/** Request-scoped routing boundary supplied by data protection and data-boundary policies. */
public record RoutingConstraint(
        boolean externalNoRetentionGpuAllowed,
        boolean externalApiAllowed,
        boolean externalFailoverAllowed,
        List<String> reasonCodes
) {
    public RoutingConstraint {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public RoutingConstraint(boolean externalAllowed, boolean externalFailoverAllowed, List<String> reasonCodes) {
        this(externalAllowed, externalAllowed, externalFailoverAllowed, reasonCodes);
    }

    public boolean externalAllowed() {
        return externalNoRetentionGpuAllowed || externalApiAllowed;
    }

    public static RoutingConstraint unrestricted() {
        return new RoutingConstraint(true, true, true, List.of());
    }

    public Set<String> reasons() { return Set.copyOf(reasonCodes); }
}
