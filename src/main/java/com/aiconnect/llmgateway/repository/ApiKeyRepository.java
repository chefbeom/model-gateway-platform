package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.ApiKeyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyPrefix(String keyPrefix);
    List<ApiKey> findByProjectId(UUID projectId);
    List<ApiKey> findByProjectIdIn(List<UUID> projectIds);
    List<ApiKey> findByIssuedByUserId(UUID issuedByUserId);
    List<ApiKey> findByProjectIdAndIssuedByUserIdAndStatus(UUID projectId, UUID issuedByUserId, ApiKeyStatus status);
    List<ApiKey> findByIssuedByUserIdAndStatus(UUID issuedByUserId, ApiKeyStatus status);
    long countByProjectIdAndIssuedByUserIdAndStatus(UUID projectId, UUID issuedByUserId, ApiKeyStatus status);
    @Modifying
    @Query("update ApiKey k set k.lastUsedAt = :now where k.id = :id and k.status = :active and (k.expiresAt is null or k.expiresAt > :now)")
    int markUsedIfActive(@Param("id") UUID id, @Param("active") ApiKeyStatus active, @Param("now") Instant now);
}
