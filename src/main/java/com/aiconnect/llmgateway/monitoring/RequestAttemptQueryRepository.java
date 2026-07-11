package com.aiconnect.llmgateway.monitoring;

import com.aiconnect.llmgateway.domain.LlmRequestAttempt;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RequestAttemptQueryRepository extends Repository<LlmRequestAttempt, UUID> {
    @Query("""
            select a.deploymentId as deploymentId, a.attemptNumber as attemptNumber, a.status as status,
                   a.startedAt as startedAt, a.completedAt as completedAt, a.latencyMs as latencyMs,
                   a.httpStatus as httpStatus, a.errorType as errorType, a.errorMessage as errorMessage,
                   a.responseStarted as responseStarted
            from LlmRequestAttempt a where a.requestId = :requestId order by a.attemptNumber
            """)
    List<AttemptProjection> findAttempts(@Param("requestId") UUID requestId);

    interface AttemptProjection {
        UUID getDeploymentId();
        int getAttemptNumber();
        String getStatus();
        Instant getStartedAt();
        Instant getCompletedAt();
        Long getLatencyMs();
        Integer getHttpStatus();
        String getErrorType();
        String getErrorMessage();
        boolean isResponseStarted();
    }
}
