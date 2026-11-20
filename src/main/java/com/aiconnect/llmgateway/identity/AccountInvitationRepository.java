package com.aiconnect.llmgateway.identity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountInvitationRepository extends JpaRepository<AccountInvitation, UUID> {
    Optional<AccountInvitation> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from AccountInvitation i where i.tokenHash = :tokenHash")
    Optional<AccountInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from AccountInvitation i where i.id = :id")
    Optional<AccountInvitation> findByIdForUpdate(@Param("id") UUID id);

    List<AccountInvitation> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
    List<AccountInvitation> findAllByOrderByCreatedAtDesc();
    List<AccountInvitation> findByProjectIdAndIssuedByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(UUID projectId, UUID issuedByUserId);
}
