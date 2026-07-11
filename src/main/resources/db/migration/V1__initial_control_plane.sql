CREATE TABLE organization (
    id CHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_organization_name UNIQUE (name)
);

CREATE TABLE project (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_project_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT uk_project_organization_name UNIQUE (organization_id, name)
);

CREATE TABLE api_key (
    id CHAR(36) NOT NULL PRIMARY KEY,
    project_id CHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    key_prefix VARCHAR(48) NOT NULL,
    secret_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMP(6) NULL,
    last_used_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_api_key_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT uk_api_key_prefix UNIQUE (key_prefix)
);

CREATE TABLE inference_node (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    connection_mode VARCHAR(24) NOT NULL DEFAULT 'DIRECT',
    status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    labels_json TEXT NULL,
    last_heartbeat_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_node_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT uk_node_organization_name UNIQUE (organization_id, name)
);

CREATE TABLE runtime_endpoint (
    id CHAR(36) NOT NULL PRIMARY KEY,
    node_id CHAR(36) NOT NULL,
    runtime_type VARCHAR(40) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    encrypted_api_token TEXT NULL,
    runtime_version VARCHAR(80) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    health_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    last_checked_at TIMESTAMP(6) NULL,
    last_success_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_endpoint_node FOREIGN KEY (node_id) REFERENCES inference_node(id),
    CONSTRAINT uk_endpoint_base_url UNIQUE (base_url)
);

CREATE TABLE model_deployment (
    id CHAR(36) NOT NULL PRIMARY KEY,
    runtime_endpoint_id CHAR(36) NOT NULL,
    provider_model_id VARCHAR(500) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    model_family VARCHAR(120) NULL,
    quantization VARCHAR(60) NULL,
    context_length INT NULL,
    loaded BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    health_status VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN',
    max_concurrency INT NOT NULL DEFAULT 1,
    capabilities_json TEXT NULL,
    metadata_json TEXT NULL,
    last_synced_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_deployment_endpoint FOREIGN KEY (runtime_endpoint_id) REFERENCES runtime_endpoint(id),
    CONSTRAINT uk_deployment_endpoint_model UNIQUE (runtime_endpoint_id, provider_model_id)
);

CREATE TABLE llm_service (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    service_key VARCHAR(120) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    failover_policy VARCHAR(24) NOT NULL DEFAULT 'STRICT',
    allow_degraded BOOLEAN NOT NULL DEFAULT FALSE,
    required_capabilities_json TEXT NULL,
    input_price_per_million DECIMAL(18,6) NOT NULL DEFAULT 0,
    output_price_per_million DECIMAL(18,6) NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_service_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT uk_service_organization_key UNIQUE (organization_id, service_key)
);

CREATE TABLE project_service_access (
    project_id CHAR(36) NOT NULL,
    service_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (project_id, service_id),
    CONSTRAINT fk_access_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_access_service FOREIGN KEY (service_id) REFERENCES llm_service(id)
);

CREATE TABLE service_target (
    id CHAR(36) NOT NULL PRIMARY KEY,
    service_id CHAR(36) NOT NULL,
    deployment_id CHAR(36) NOT NULL,
    priority INT NOT NULL,
    weight INT NOT NULL DEFAULT 100,
    degraded BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_concurrency_override INT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_target_service FOREIGN KEY (service_id) REFERENCES llm_service(id),
    CONSTRAINT fk_target_deployment FOREIGN KEY (deployment_id) REFERENCES model_deployment(id),
    CONSTRAINT uk_target_service_deployment UNIQUE (service_id, deployment_id)
);

CREATE TABLE llm_request (
    id CHAR(36) NOT NULL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    project_id CHAR(36) NOT NULL,
    api_key_id CHAR(36) NOT NULL,
    service_id CHAR(36) NOT NULL,
    final_deployment_id CHAR(36) NULL,
    endpoint VARCHAR(120) NOT NULL,
    request_type VARCHAR(60) NOT NULL,
    stream BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    estimated_cost DECIMAL(18,6) NULL,
    input_unit_price DECIMAL(18,6) NOT NULL,
    output_unit_price DECIMAL(18,6) NOT NULL,
    latency_ms BIGINT NULL,
    failover_count INT NOT NULL DEFAULT 0,
    http_status INT NULL,
    error_code VARCHAR(80) NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_request_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_request_api_key FOREIGN KEY (api_key_id) REFERENCES api_key(id),
    CONSTRAINT fk_request_service FOREIGN KEY (service_id) REFERENCES llm_service(id),
    CONSTRAINT fk_request_deployment FOREIGN KEY (final_deployment_id) REFERENCES model_deployment(id),
    CONSTRAINT uk_request_id UNIQUE (request_id),
    INDEX idx_request_project_started (project_id, started_at),
    INDEX idx_request_service_started (service_id, started_at),
    INDEX idx_request_status_started (status, started_at)
);

CREATE TABLE llm_request_attempt (
    id CHAR(36) NOT NULL PRIMARY KEY,
    request_id CHAR(36) NOT NULL,
    deployment_id CHAR(36) NOT NULL,
    attempt_number INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    latency_ms BIGINT NULL,
    http_status INT NULL,
    error_type VARCHAR(80) NULL,
    error_message VARCHAR(1000) NULL,
    response_started BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_attempt_request FOREIGN KEY (request_id) REFERENCES llm_request(id),
    CONSTRAINT fk_attempt_deployment FOREIGN KEY (deployment_id) REFERENCES model_deployment(id),
    CONSTRAINT uk_attempt_number UNIQUE (request_id, attempt_number)
);

CREATE TABLE incident (
    id CHAR(36) NOT NULL PRIMARY KEY,
    runtime_endpoint_id CHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    opened_at TIMESTAMP(6) NOT NULL,
    recovered_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_incident_endpoint FOREIGN KEY (runtime_endpoint_id) REFERENCES runtime_endpoint(id),
    INDEX idx_incident_status_opened (status, opened_at)
);
