package com.aiconnect.llmgateway.security;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConnectionRegistrationRequestRepository extends JpaRepository<ConnectionRegistrationRequest, UUID> {
    List<ConnectionRegistrationRequest> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    List<ConnectionRegistrationRequest> findByStatusOrderByCreatedAtDesc(ConnectionRegistrationRequest.Status status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ConnectionRegistrationRequest r where r.id = :id")
    Optional<ConnectionRegistrationRequest> findForUpdate(@Param("id") UUID id);
}
