package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tombstones a logical service after operational references have been removed. */
@Service
public class ServiceDeletionService {
    private final LlmServiceRepository services;
    private final AuditService audit;
    private final EntityManager entityManager;

    public ServiceDeletionService(LlmServiceRepository services, AuditService audit, EntityManager entityManager) {
        this.services = services;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public DeletionCheck inspect(UUID serviceId) {
        LlmService service = requireService(serviceId);
        long projectAccessCount = count("select count(a) from ProjectServiceAccess a where a.id.serviceId = :serviceId", serviceId);
        long targetCount = count("select count(t) from ServiceTarget t where t.serviceId = :serviceId", serviceId);
        long requestHistoryCount = count("select count(r) from LlmRequest r where r.serviceId = :serviceId", serviceId);
        List<String> projectNames = entityManager.createQuery("""
                        select p.name
                        from ProjectServiceAccess a, Project p
                        where a.id.projectId = p.id and a.id.serviceId = :serviceId
                        order by p.name
                        """, String.class)
                .setParameter("serviceId", serviceId)
                .setMaxResults(8)
                .getResultList();
        boolean canDelete = projectAccessCount == 0 && targetCount == 0;
        return new DeletionCheck(service.getId(), service.getServiceKey(), service.getDisplayName(),
                projectAccessCount, targetCount, requestHistoryCount, projectNames, canDelete);
    }

    @Transactional
    public void deleteIfUnused(UUID serviceId) {
        DeletionCheck check = inspect(serviceId);
        if (!check.canDelete()) throw blocked(check);
        LlmService service = requireService(serviceId);
        service.markDeleted();
        services.saveAndFlush(service);
        audit.record(service.getOrganizationId(), CurrentActor.userIdOrNull(), "SERVICE_DELETED", "LLM_SERVICE", serviceId,
                Map.of("serviceKey", service.getServiceKey(), "requestHistoryCount", check.requestHistoryCount()));
    }

    private long count(String query, UUID serviceId) {
        return entityManager.createQuery(query, Long.class)
                .setParameter("serviceId", serviceId)
                .getSingleResult();
    }

    private LlmService requireService(UUID serviceId) {
        return services.findById(serviceId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "SERVICE_NOT_FOUND", "논리 서비스를 찾을 수 없습니다."));
    }

    private ApiException blocked(DeletionCheck check) {
        List<String> blockers = new ArrayList<>();
        if (check.projectAccessCount() > 0) blockers.add("프로젝트 권한 " + check.projectAccessCount() + "개");
        if (check.targetCount() > 0) blockers.add("Service Target " + check.targetCount() + "개");
        return new ApiException(HttpStatus.CONFLICT, "SERVICE_DELETE_BLOCKED",
                "'" + check.serviceKey() + "' 서비스는 " + String.join(", ", blockers) + "이(가) 연결되어 있어 삭제할 수 없습니다.");
    }

    public record DeletionCheck(UUID serviceId, String serviceKey, String displayName,
                                long projectAccessCount, long targetCount, long requestHistoryCount,
                                List<String> linkedProjectNames, boolean canDelete) { }
}
