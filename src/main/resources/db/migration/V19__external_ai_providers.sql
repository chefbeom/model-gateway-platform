CREATE TABLE external_provider (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    provider_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    encrypted_api_key TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    health_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    last_checked_at TIMESTAMP(6) NULL,
    last_success_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_external_provider_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT uk_external_provider_org_name UNIQUE (organization_id, display_name)
);

CREATE TABLE project_external_access (
    id CHAR(36) NOT NULL PRIMARY KEY,
    project_id CHAR(36) NOT NULL,
    provider_id CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    requested_by_user_id CHAR(36) NULL,
    requested_reason VARCHAR(1000) NOT NULL,
    manual_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    auto_failover_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    monthly_cost_limit DECIMAL(18,6) NULL,
    expires_at TIMESTAMP(6) NULL,
    approved_by_user_id CHAR(36) NULL,
    decided_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_project_external_access_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_external_access_provider FOREIGN KEY (provider_id) REFERENCES external_provider(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_external_access_requester FOREIGN KEY (requested_by_user_id) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT fk_project_external_access_approver FOREIGN KEY (approved_by_user_id) REFERENCES app_user(id) ON DELETE SET NULL,
    CONSTRAINT uk_project_external_provider UNIQUE (project_id, provider_id),
    INDEX idx_external_access_status (status, updated_at)
);

ALTER TABLE model_deployment MODIFY COLUMN runtime_endpoint_id CHAR(36) NULL;
ALTER TABLE model_deployment
    ADD COLUMN external_provider_id CHAR(36) NULL AFTER runtime_endpoint_id,
    ADD COLUMN provider_input_price_per_million DECIMAL(18,6) NULL AFTER max_concurrency,
    ADD COLUMN provider_output_price_per_million DECIMAL(18,6) NULL AFTER provider_input_price_per_million,
    ADD CONSTRAINT fk_deployment_external_provider FOREIGN KEY (external_provider_id) REFERENCES external_provider(id),
    ADD CONSTRAINT uk_deployment_external_provider_model UNIQUE (external_provider_id, provider_model_id);

ALTER TABLE llm_request
    ADD COLUMN final_provider_type VARCHAR(40) NULL AFTER final_deployment_id,
    ADD COLUMN routing_reason VARCHAR(60) NULL AFTER final_provider_type;

CREATE INDEX idx_external_provider_org ON external_provider(organization_id);
CREATE INDEX idx_deployment_external_provider ON model_deployment(external_provider_id);
