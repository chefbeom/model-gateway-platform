package com.aiconnect.llmgateway.dataprotection;

import com.aiconnect.llmgateway.routing.RoutingConstraint;

import java.util.List;

public record DataProtectionDecision(
        EffectiveDataProtectionPolicy policy,
        DataProtectionScanResult scan,
        DataProtectionAction action,
        boolean externalAllowed,
        boolean externalFailoverAllowed,
        List<String> reasonCodes
) {
    public boolean blocked() { return action == DataProtectionAction.BLOCK; }

    public RoutingConstraint routingConstraint() {
        return new RoutingConstraint(externalAllowed, externalFailoverAllowed, reasonCodes);
    }

    public String classificationSummary() {
        return String.join(",", scan.labels());
    }
}
