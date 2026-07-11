package com.aiconnect.llmgateway.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, OrganizationMemberId> {
    Optional<OrganizationMember> findByIdOrganizationIdAndIdUserId(UUID organizationId, UUID userId);
    List<OrganizationMember> findByIdOrganizationId(UUID organizationId);
    List<OrganizationMember> findByIdUserId(UUID userId);
    long countByIdOrganizationIdAndRole(UUID organizationId, OrganizationRole role);
}
