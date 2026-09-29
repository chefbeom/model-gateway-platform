ALTER TABLE playground_request
    ADD COLUMN request_type VARCHAR(60) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN reasoning_effort VARCHAR(24) NULL,
    ADD COLUMN requested_service_tier VARCHAR(24) NULL;
