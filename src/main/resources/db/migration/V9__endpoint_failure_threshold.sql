ALTER TABLE runtime_endpoint
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0;

ALTER TABLE runtime_endpoint
    ADD COLUMN failure_threshold INT NOT NULL DEFAULT 3;
