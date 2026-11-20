CREATE TABLE project_runtime_approval (
    id CHAR(36) NOT NULL PRIMARY KEY,
    project_id CHAR(36) NOT NULL,
    endpoint_id CHAR(36) NOT NULL,
    attestation_decided_at TIMESTAMP(6) NOT NULL,
    manual_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    failover_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP(6) NOT NULL,
    approved_by CHAR(36) NULL,
    UNIQUE (project_id, endpoint_id),
    CONSTRAINT fk_project_runtime_approval_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_runtime_approval_endpoint FOREIGN KEY (endpoint_id) REFERENCES runtime_endpoint(id) ON DELETE CASCADE
);
