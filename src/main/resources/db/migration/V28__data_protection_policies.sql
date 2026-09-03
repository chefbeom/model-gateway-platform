CREATE TABLE data_protection_policy (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_id CHAR(36) NULL,
    mode VARCHAR(16) NOT NULL DEFAULT 'OFF',
    level VARCHAR(16) NOT NULL DEFAULT 'RELAXED',
    external_action VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
    allow_external_failover BOOLEAN NOT NULL DEFAULT TRUE,
    detect_secrets BOOLEAN NOT NULL DEFAULT TRUE,
    detect_pii BOOLEAN NOT NULL DEFAULT FALSE,
    detect_financial BOOLEAN NOT NULL DEFAULT FALSE,
    detect_confidential BOOLEAN NOT NULL DEFAULT FALSE,
    detect_media BOOLEAN NOT NULL DEFAULT TRUE,
    custom_patterns_json TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_data_protection_policy_org FOREIGN KEY (organization_id)
        REFERENCES organization(id) ON DELETE CASCADE,
    INDEX idx_data_protection_policy_org (organization_id),
    INDEX idx_data_protection_policy_scope (scope_type, scope_id)
);

ALTER TABLE llm_request
    ADD COLUMN data_protection_mode VARCHAR(16) NULL,
    ADD COLUMN data_protection_level VARCHAR(16) NULL,
    ADD COLUMN data_protection_action VARCHAR(16) NULL,
    ADD COLUMN data_classifications_json TEXT NULL,
    ADD COLUMN data_external_allowed BOOLEAN NULL;
