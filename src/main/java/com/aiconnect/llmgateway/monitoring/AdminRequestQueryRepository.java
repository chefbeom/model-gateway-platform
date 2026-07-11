package com.aiconnect.llmgateway.monitoring;

import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;

public interface AdminRequestQueryRepository extends Repository<LlmRequest, UUID> {
    @Query(value = """
            select r from LlmRequest r, Project p
            where r.projectId = p.id and p.organizationId = :organizationId
              and (:projectId is null or r.projectId = :projectId)
              and (:serviceId is null or r.serviceId = :serviceId)
              and (:deploymentId is null or r.finalDeploymentId = :deploymentId)
              and (:status is null or r.status = :status)
              and (:failoverOnly = false or r.failoverCount > 0)
              and (:fromTime is null or r.startedAt >= :fromTime)
              and (:toTime is null or r.startedAt < :toTime)
            order by r.startedAt desc
            """,
            countQuery = """
            select count(r) from LlmRequest r, Project p
            where r.projectId = p.id and p.organizationId = :organizationId
              and (:projectId is null or r.projectId = :projectId)
              and (:serviceId is null or r.serviceId = :serviceId)
              and (:deploymentId is null or r.finalDeploymentId = :deploymentId)
              and (:status is null or r.status = :status)
              and (:failoverOnly = false or r.failoverCount > 0)
              and (:fromTime is null or r.startedAt >= :fromTime)
              and (:toTime is null or r.startedAt < :toTime)
            """)
    Page<LlmRequest> search(@Param("organizationId") UUID organizationId,
                            @Param("projectId") UUID projectId,
                            @Param("serviceId") UUID serviceId,
                            @Param("deploymentId") UUID deploymentId,
                            @Param("status") RequestStatus status,
                            @Param("failoverOnly") boolean failoverOnly,
                            @Param("fromTime") Instant fromTime,
                            @Param("toTime") Instant toTime,
                            Pageable pageable);
}
