ALTER TABLE llm_service
    ADD COLUMN deleted_at TIMESTAMP(6) NULL;

CREATE INDEX idx_service_organization_deleted
    ON llm_service(organization_id, deleted_at);
