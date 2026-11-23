ALTER TABLE external_provider
    ADD COLUMN zero_retention_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN zero_retention_evidence VARCHAR(500) NULL,
    ADD COLUMN zero_retention_verified_at TIMESTAMP(6) NULL;

ALTER TABLE llm_service
    ADD COLUMN masker_deployment_id CHAR(36) NULL,
    ADD COLUMN mask_person_names BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN system_prompt_template TEXT NULL,
    ADD COLUMN prompt_version INT NOT NULL DEFAULT 1,
    ADD COLUMN response_cache_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN response_cache_ttl_seconds INT NOT NULL DEFAULT 300;
