CREATE TABLE runtime_trust_attestation (
    endpoint_id CHAR(36) NOT NULL PRIMARY KEY,
    classification VARCHAR(32) NOT NULL,
    endpoint_url VARCHAR(500) NOT NULL,
    evidence VARCHAR(1000) NOT NULL,
    expires_at TIMESTAMP(6) NULL,
    approved_by CHAR(36) NULL,
    decided_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_runtime_attestation_endpoint FOREIGN KEY (endpoint_id) REFERENCES runtime_endpoint(id) ON DELETE CASCADE
);
