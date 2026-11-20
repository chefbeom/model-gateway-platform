ALTER TABLE project_external_access
    ADD COLUMN monthly_cost_currency VARCHAR(10) NULL;

CREATE TABLE project_external_access_reservation (
    id CHAR(36) NOT NULL PRIMARY KEY,
    project_id CHAR(36) NOT NULL,
    provider_id CHAR(36) NOT NULL,
    reservation_key CHAR(36) NOT NULL,
    request_id VARCHAR(64) NULL,
    amount DECIMAL(18, 6) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    period_start TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    settled_at TIMESTAMP(6) NULL,
    reason VARCHAR(120) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_pear_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT fk_pear_provider FOREIGN KEY (provider_id) REFERENCES external_provider(id) ON DELETE CASCADE,
    CONSTRAINT ck_pear_amount_nonnegative CHECK (amount >= 0)
);
CREATE INDEX idx_pear_proj_prov_status_exp
    ON project_external_access_reservation (project_id, provider_id, status, expires_at);
CREATE INDEX idx_pear_key ON project_external_access_reservation (reservation_key);

ALTER TABLE spend_quota
    ADD COLUMN admin_ceiling BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE spend_quota SET admin_ceiling = TRUE;

ALTER TABLE spend_quota_reservation
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN settled_at TIMESTAMP(6) NULL,
    ADD COLUMN reason VARCHAR(120) NULL;
CREATE INDEX idx_sqr_quota_status_exp
    ON spend_quota_reservation (quota_id, status, expires_at);

ALTER TABLE project_quota
    ADD COLUMN max_requests_per_minute INT NULL,
    ADD COLUMN max_monthly_token_limit BIGINT NULL;
