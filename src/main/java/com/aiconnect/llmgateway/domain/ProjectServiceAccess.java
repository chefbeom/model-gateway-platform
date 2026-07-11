package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_service_access")
public class ProjectServiceAccess {
    @EmbeddedId private ProjectServiceAccessId id;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    protected ProjectServiceAccess() { }
    public ProjectServiceAccess(UUID projectId, UUID serviceId) { id = new ProjectServiceAccessId(projectId, serviceId); }
    public ProjectServiceAccessId getId() { return id; }
}
