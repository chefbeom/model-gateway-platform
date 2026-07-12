ALTER TABLE api_key
    ADD COLUMN issued_by_user_id CHAR(36) NULL AFTER project_id;

ALTER TABLE api_key
    ADD CONSTRAINT fk_api_key_issued_by_user
        FOREIGN KEY (issued_by_user_id) REFERENCES app_user(id);

CREATE INDEX idx_api_key_issued_by_user
    ON api_key (issued_by_user_id);
