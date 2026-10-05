package com.aiconnect.llmgateway.repository;

import com.aiconnect.llmgateway.domain.PlaygroundConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaygroundConversationRepository extends JpaRepository<PlaygroundConversation, UUID> {
    List<PlaygroundConversation> findByOrganizationIdAndActorUserIdOrderByUpdatedAtDesc(
            UUID organizationId, UUID actorUserId);

    Optional<PlaygroundConversation> findByIdAndOrganizationIdAndActorUserId(
            UUID id, UUID organizationId, UUID actorUserId);
}
