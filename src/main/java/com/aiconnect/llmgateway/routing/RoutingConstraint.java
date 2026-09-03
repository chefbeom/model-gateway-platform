package com.aiconnect.llmgateway.routing;

import java.util.List;
import java.util.Set;

/** Request-scoped routing boundary supplied by data protection or future policy modules. */
public record RoutingConstraint(boolean externalAllowed, boolean externalFailoverAllowed,
                                List<String> reasonCodes) {
    public RoutingConstraint {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }

    public static RoutingConstraint unrestricted() {
        return new RoutingConstraint(true, true, List.of());
    }

    public Set<String> reasons() { return Set.copyOf(reasonCodes); }
}
