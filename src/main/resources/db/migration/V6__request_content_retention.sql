CREATE TABLE project_content_policy (
    project_id CHAR(36) NOT NULL PRIMARY KEY,
    retention_mode VARCHAR(32) NOT NULL DEFAULT 'METADATA_ONLY',
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_content_policy_project FOREIGN KEY (project_id) REFERENCES project(id)
);

CREATE TABLE llm_request_content (
    request_id CHAR(36) NOT NULL PRIMARY KEY,
    encrypted_request TEXT NOT NULL,
    encrypted_response TEXT NULL,
    captured_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_request_content_request FOREIGN KEY (request_id) REFERENCES llm_request(id)
);
