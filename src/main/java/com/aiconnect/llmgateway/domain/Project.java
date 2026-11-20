package com.aiconnect.llmgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project")
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(nullable = false, columnDefinition = "char(36)")
    private UUID organizationId;

    @Column(columnDefinition = "char(36)")
    private UUID teamId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 24)
    private String status = "ACTIVE";

    /** Hard project boundary, independent of sensitive-data detection. */
    @Column(nullable = false)
    private boolean externalAiBlocked;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_boundary_policy", nullable = false, length = 40)
    private DataBoundaryPolicy dataBoundaryPolicy = DataBoundaryPolicy.EXTERNAL_ALLOWED;

    @Enumerated(EnumType.STRING)
    @Column(name = "max_data_boundary_policy", nullable = false, length = 40)
    private DataBoundaryPolicy maxDataBoundaryPolicy = DataBoundaryPolicy.EXTERNAL_ALLOWED;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected Project() { }

    public Project(UUID organizationId, String name) {
        this(organizationId, null, name);
    }

    public Project(UUID organizationId, UUID teamId, String name) {
        this.organizationId = organizationId;
        this.teamId = teamId;
        this.name = name;
    }

    @PreUpdate
    void updateTimestamp() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getTeamId() { return teamId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public boolean isExternalAiBlocked() {
        return externalAiBlocked || effectiveDataBoundaryPolicy() == DataBoundaryPolicy.INTERNAL_ONLY;
    }

    public DataBoundaryPolicy getDataBoundaryPolicy() {
        if (externalAiBlocked) {
            return DataBoundaryPolicy.INTERNAL_ONLY;
        }
        return dataBoundaryPolicy == null ? DataBoundaryPolicy.EXTERNAL_ALLOWED : dataBoundaryPolicy;
    }

    public DataBoundaryPolicy getMaxDataBoundaryPolicy() {
        return maxDataBoundaryPolicy == null ? DataBoundaryPolicy.EXTERNAL_ALLOWED : maxDataBoundaryPolicy;
    }

    public DataBoundaryPolicy effectiveDataBoundaryPolicy() {
        return DataBoundaryPolicy.strictest(getMaxDataBoundaryPolicy(), getDataBoundaryPolicy());
    }

    public void configureExternalAiBlocked(boolean blocked) {
        this.externalAiBlocked = blocked;
        if (blocked) {
            this.dataBoundaryPolicy = DataBoundaryPolicy.INTERNAL_ONLY;
            this.maxDataBoundaryPolicy = DataBoundaryPolicy.INTERNAL_ONLY;
        } else {
            if (this.maxDataBoundaryPolicy == null || this.maxDataBoundaryPolicy == DataBoundaryPolicy.INTERNAL_ONLY) {
                this.maxDataBoundaryPolicy = DataBoundaryPolicy.EXTERNAL_ALLOWED;
            }
            this.dataBoundaryPolicy = this.maxDataBoundaryPolicy;
        }
    }

    public void configureDataBoundaryPolicy(DataBoundaryPolicy policy, DataBoundaryPolicy ceiling) {
        if (ceiling != null) {
            this.maxDataBoundaryPolicy = ceiling;
        }
        DataBoundaryPolicy effectiveCeiling = getMaxDataBoundaryPolicy();
        if (policy != null) {
            this.dataBoundaryPolicy = policy.exceeds(effectiveCeiling) ? effectiveCeiling : policy;
        } else if (this.dataBoundaryPolicy == null || this.dataBoundaryPolicy.exceeds(effectiveCeiling)) {
            this.dataBoundaryPolicy = effectiveCeiling;
        }
        this.externalAiBlocked = DataBoundaryPolicy.strictest(effectiveCeiling, this.dataBoundaryPolicy) == DataBoundaryPolicy.INTERNAL_ONLY;
    }

    public void updateMetadata(String name, UUID teamId) {
        if (name != null && !name.isBlank()) {
            this.name = name.trim();
        }
        this.teamId = teamId;
    }

    /**
     * Retains request and usage history after an owning team is removed, while
     * ensuring that the former team project cannot continue to accept traffic.
     */
    public void unassignTeamAndSuspend() {
        teamId = null;
        status = "SUSPENDED";
    }
}
