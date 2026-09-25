ALTER TABLE llm_service
    ADD COLUMN temperature_policy VARCHAR(24) NOT NULL DEFAULT 'REQUEST';

ALTER TABLE llm_service
    ADD COLUMN temperature_value DECIMAL(4, 3) NULL;
