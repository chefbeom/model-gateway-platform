package com.aiconnect.llmgateway.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project")
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID organizationId;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 24) private String status = "ACTIVE";
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected Project() { }
    public Project(UUID organizationId, String name) { this.organizationId = organizationId; this.name = name; }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
}
