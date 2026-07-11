package com.aiconnect.llmgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ProjectServiceAccessId implements Serializable {
    @Column(name = "project_id", columnDefinition = "char(36)") private UUID projectId;
    @Column(name = "service_id", columnDefinition = "char(36)") private UUID serviceId;
    protected ProjectServiceAccessId() { }
    public ProjectServiceAccessId(UUID projectId, UUID serviceId) { this.projectId = projectId; this.serviceId = serviceId; }
    public UUID getProjectId() { return projectId; }
    public UUID getServiceId() { return serviceId; }
    @Override public boolean equals(Object other) { return other instanceof ProjectServiceAccessId id && Objects.equals(projectId, id.projectId) && Objects.equals(serviceId, id.serviceId); }
    @Override public int hashCode() { return Objects.hash(projectId, serviceId); }
}
