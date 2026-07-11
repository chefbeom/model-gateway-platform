package com.aiconnect.llmgateway.retention;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface RequestContentRepository extends JpaRepository<RequestContent, UUID> {
    @Modifying
    @Query("""
            delete from RequestContent c where c.requestId in (
                select r.id from LlmRequest r where r.projectId = :projectId and r.startedAt < :cutoff
            )
            """)
    int deleteExpiredForProject(@Param("projectId") UUID projectId, @Param("cutoff") Instant cutoff);

    @Modifying
    @Query("""
            delete from RequestContent c where c.requestId in (
                select r.id from LlmRequest r where r.projectId = :projectId
            )
            """)
    int deleteAllForProject(@Param("projectId") UUID projectId);
}
