package com.aiconnect.llmgateway.beta;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface BetaInvitationRepository extends JpaRepository<BetaInvitation,UUID> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from BetaInvitation i where i.tokenHash = :hash") Optional<BetaInvitation> findLockedByHash(@Param("hash") String hash);
 List<BetaInvitation> findByServiceId(UUID serviceId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from BetaInvitation i where i.id = :id and i.serviceId = :service") Optional<BetaInvitation> findLocked(@Param("id") UUID id,@Param("service") UUID service);
}
