package com.aiconnect.llmgateway.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AuditLogQueryController {
    private final AuditLogQueryService auditLogs;

    public AuditLogQueryController(AuditLogQueryService auditLogs) { this.auditLogs = auditLogs; }

    @GetMapping("/organizations/{organizationId}/audit-logs")
    public AuditLogQueryService.PageResult organizationLogs(@PathVariable UUID organizationId,
                                                             @RequestParam(required = false) String action,
                                                             @RequestParam(required = false) String resourceType,
                                                             @RequestParam(required = false) UUID actorUserId,
                                                             @RequestParam(required = false) Instant from,
                                                             @RequestParam(required = false) Instant to,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "50") int size) {
        return auditLogs.search(organizationId, action, resourceType, actorUserId, from, to, page, size);
    }

    @GetMapping("/audit-logs")
    public AuditLogQueryService.PageResult platformLogs(@RequestParam(required = false) String action,
                                                         @RequestParam(required = false) String resourceType,
                                                         @RequestParam(required = false) UUID actorUserId,
                                                         @RequestParam(required = false) Instant from,
                                                         @RequestParam(required = false) Instant to,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "50") int size) {
        return auditLogs.search(null, action, resourceType, actorUserId, from, to, page, size);
    }
}
