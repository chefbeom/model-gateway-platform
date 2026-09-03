CREATE TABLE llm_request_diagnostic (
    request_id CHAR(36) NOT NULL PRIMARY KEY,
    schema_version INT NOT NULL DEFAULT 1,
    payload_json MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_request_diagnostic_request FOREIGN KEY (request_id)
        REFERENCES llm_request(id) ON DELETE CASCADE
);
