package com.aiconnect.llmgateway.quota;

/** Thread-local carrier for the active spend quota reservation across servlet filters and gateway entrypoints. */
public final class SpendQuotaContext {
    private static final ThreadLocal<SpendQuotaService.Reservation> CURRENT = new ThreadLocal<>();

    private SpendQuotaContext() { }

    public static SpendQuotaService.Reservation current() {
        return CURRENT.get();
    }

    public static SpendQuotaService.Reservation bind(SpendQuotaService.Reservation reservation) {
        SpendQuotaService.Reservation previous = CURRENT.get();
        if (reservation == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(reservation);
        }
        return previous;
    }

    public static void restore(SpendQuotaService.Reservation previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
