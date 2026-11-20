package com.aiconnect.llmgateway.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserTotpRecoveryCodeRepository extends JpaRepository<UserTotpRecoveryCode, UUID> {
    Optional<UserTotpRecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNull(UUID userId, String codeHash);
    List<UserTotpRecoveryCode> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
