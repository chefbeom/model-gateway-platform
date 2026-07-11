package com.aiconnect.llmgateway.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization_member")
public class OrganizationMember {
    @EmbeddedId private OrganizationMemberId id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private OrganizationRole role;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    protected OrganizationMember() { }
    public OrganizationMember(UUID organizationId, UUID userId, OrganizationRole role) { this.id = new OrganizationMemberId(organizationId, userId); this.role = role; }
    public OrganizationMemberId getId() { return id; }
    public OrganizationRole getRole() { return role; }
}
