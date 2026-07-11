package com.aiconnect.llmgateway.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditLogRepository logs;
    private final ObjectMapper objectMapper;
    public AuditService(AuditLogRepository logs, ObjectMapper objectMapper) { this.logs = logs; this.objectMapper = objectMapper; }
    @Transactional
    public void record(UUID organizationId, UUID actorId, String action, String resourceType, UUID resourceId, Map<String, ?> details) {
        try { logs.save(new AuditLog(organizationId, actorId, action, resourceType, resourceId == null ? null : resourceId.toString(), objectMapper.writeValueAsString(details))); }
        catch (Exception exception) { throw new IllegalStateException("Could not write the audit event", exception); }
    }
}
