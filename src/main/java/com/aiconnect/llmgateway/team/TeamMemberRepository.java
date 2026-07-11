package com.aiconnect.llmgateway.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMemberId> {
    Optional<TeamMember> findByIdTeamIdAndIdUserId(UUID teamId, UUID userId);
    List<TeamMember> findByIdTeamId(UUID teamId);
    List<TeamMember> findByIdUserId(UUID userId);
}
