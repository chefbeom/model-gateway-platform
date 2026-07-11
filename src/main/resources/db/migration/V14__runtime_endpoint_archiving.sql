ALTER TABLE runtime_endpoint
    ADD COLUMN archived_at TIMESTAMP(6) NULL;

ALTER TABLE runtime_endpoint
    DROP INDEX uk_endpoint_base_url;

CREATE INDEX idx_runtime_endpoint_active_url
    ON runtime_endpoint (base_url, archived_at);
