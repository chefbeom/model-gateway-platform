CREATE TABLE runtime_model_profile (
    id CHAR(36) NOT NULL PRIMARY KEY,
    runtime_endpoint_id CHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    model_key VARCHAR(500) NOT NULL,
    config_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_runtime_profile_endpoint FOREIGN KEY (runtime_endpoint_id) REFERENCES runtime_endpoint(id),
    CONSTRAINT uk_runtime_profile_endpoint_name UNIQUE (runtime_endpoint_id, name)
);

CREATE TABLE runtime_model_operation (
    id CHAR(36) NOT NULL PRIMARY KEY,
    runtime_endpoint_id CHAR(36) NOT NULL,
    profile_id CHAR(36) NULL,
    model_key VARCHAR(500) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_json TEXT NULL,
    result_json TEXT NULL,
    message VARCHAR(1000) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_runtime_operation_endpoint FOREIGN KEY (runtime_endpoint_id) REFERENCES runtime_endpoint(id),
    CONSTRAINT fk_runtime_operation_profile FOREIGN KEY (profile_id) REFERENCES runtime_model_profile(id),
    INDEX idx_runtime_operation_endpoint_created (runtime_endpoint_id, created_at)
);
