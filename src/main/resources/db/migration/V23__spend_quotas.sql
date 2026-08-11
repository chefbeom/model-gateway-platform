CREATE TABLE spend_quota (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_id CHAR(36) NOT NULL,
    name VARCHAR(160) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'KRW',
    limit_amount DECIMAL(18,6) NOT NULL,
    period VARCHAR(16) NOT NULL DEFAULT 'MONTHLY',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_spend_quota_organization FOREIGN KEY (organization_id) REFERENCES organization(id) ON DELETE CASCADE,
    CONSTRAINT ck_spend_quota_limit_positive CHECK (limit_amount >= 0),
    INDEX idx_spend_quota_org_enabled (organization_id, enabled),
    INDEX idx_spend_quota_scope (scope_type, scope_id)
);
