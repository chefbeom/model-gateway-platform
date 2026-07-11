package com.aiconnect.llmgateway.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class OrganizationMemberId implements Serializable {
    @Column(name = "organization_id", columnDefinition = "char(36)") private UUID organizationId;
    @Column(name = "user_id", columnDefinition = "char(36)") private UUID userId;
    protected OrganizationMemberId() { }
    public OrganizationMemberId(UUID organizationId, UUID userId) { this.organizationId = organizationId; this.userId = userId; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getUserId() { return userId; }
    @Override public boolean equals(Object other) { return other instanceof OrganizationMemberId id && Objects.equals(organizationId, id.organizationId) && Objects.equals(userId, id.userId); }
    @Override public int hashCode() { return Objects.hash(organizationId, userId); }
}
