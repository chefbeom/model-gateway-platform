CREATE TABLE project_quota (
    project_id CHAR(36) NOT NULL PRIMARY KEY,
    requests_per_minute INT NOT NULL DEFAULT 60,
    monthly_token_limit BIGINT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_project_quota_project FOREIGN KEY (project_id) REFERENCES project(id)
);
