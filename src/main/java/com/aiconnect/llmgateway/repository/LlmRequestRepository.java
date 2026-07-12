package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.LlmRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LlmRequestRepository extends JpaRepository<LlmRequest, UUID> {
    long countByProjectId(UUID projectId);
    List<LlmRequest> findTop50ByProjectIdOrderByStartedAtDesc(UUID projectId);
    List<LlmRequest> findByStartedAtAfter(Instant startedAt);
    List<LlmRequest> findByProjectIdInAndStartedAtAfter(Collection<UUID> projectIds, Instant startedAt);
    List<LlmRequest> findByProjectIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
            UUID projectId, Instant from, Instant to);

    @Query("""
            select coalesce(sum(coalesce(r.inputTokens, 0) + coalesce(r.outputTokens, 0)), 0)
            from LlmRequest r
            where r.projectId = :projectId and r.startedAt >= :from
            """)
    long sumTokensByProjectSince(@Param("projectId") UUID projectId, @Param("from") Instant from);

    @Query("""
            select coalesce(sum(r.estimatedCost), 0)
            from LlmRequest r
            where r.projectId = :projectId and r.finalDeploymentId in :deploymentIds and r.startedAt >= :from
            """)
    BigDecimal sumCostByProjectAndDeploymentsSince(@Param("projectId") UUID projectId,
                                                   @Param("deploymentIds") Collection<UUID> deploymentIds,
                                                   @Param("from") Instant from);
}
