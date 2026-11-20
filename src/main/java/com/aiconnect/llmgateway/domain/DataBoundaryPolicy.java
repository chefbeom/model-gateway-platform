package com.aiconnect.llmgateway.domain;

/** Hierarchical external data boundary policy applied unconditionally across project, service, and API key tiers. */
public enum DataBoundaryPolicy {
    INTERNAL_ONLY(0),
    APPROVED_EXTERNAL_NO_RETENTION(1),
    EXTERNAL_ALLOWED(2);

    private final int rank;

    DataBoundaryPolicy(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean allowsExternalNoRetentionGpu() {
        return rank >= APPROVED_EXTERNAL_NO_RETENTION.rank;
    }

    public boolean allowsExternalApi() {
        return rank >= EXTERNAL_ALLOWED.rank;
    }

    public boolean isStricterThan(DataBoundaryPolicy other) {
        return other != null && this.rank < other.rank;
    }

    public boolean exceeds(DataBoundaryPolicy ceiling) {
        return ceiling != null && this.rank > ceiling.rank;
    }

    public static DataBoundaryPolicy strictest(DataBoundaryPolicy... policies) {
        DataBoundaryPolicy result = EXTERNAL_ALLOWED;
        if (policies == null) {
            return result;
        }
        for (DataBoundaryPolicy policy : policies) {
            if (policy != null && policy.rank < result.rank) {
                result = policy;
            }
        }
        return result;
    }
}
