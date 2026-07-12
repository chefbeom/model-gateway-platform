package com.aiconnect.llmgateway.web;

import java.util.UUID;

final class AdminAuditContext {
    private static final ThreadLocal<AdminAuditContext> CURRENT = new ThreadLocal<>();

    private UUID organizationId;

    static AdminAuditContext open() {
        AdminAuditContext context = new AdminAuditContext();
        CURRENT.set(context);
        return context;
    }

    static AdminAuditContext current() { return CURRENT.get(); }
    static void clear() { CURRENT.remove(); }

    UUID organizationId() { return organizationId; }
    void organizationId(UUID organizationId) { this.organizationId = organizationId; }
}
