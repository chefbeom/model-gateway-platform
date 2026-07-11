ALTER TABLE model_deployment
    ADD COLUMN compatibility_key VARCHAR(500) NOT NULL DEFAULT '';

UPDATE model_deployment
SET compatibility_key = provider_model_id
WHERE compatibility_key = '';

ALTER TABLE llm_service
    ADD COLUMN retry_policy VARCHAR(24) NOT NULL DEFAULT 'SAFE';
