ALTER TABLE model_deployment
    ADD COLUMN provider_cached_input_price_per_million DECIMAL(18,6) NULL,
    ADD COLUMN fast_input_price_per_million DECIMAL(18,6) NULL,
    ADD COLUMN fast_cached_input_price_per_million DECIMAL(18,6) NULL,
    ADD COLUMN fast_output_price_per_million DECIMAL(18,6) NULL,
    ADD COLUMN default_reasoning_effort VARCHAR(24) NOT NULL DEFAULT 'REQUEST',
    ADD COLUMN default_service_tier VARCHAR(16) NOT NULL DEFAULT 'REQUEST';

ALTER TABLE llm_request
    ADD COLUMN reasoning_effort VARCHAR(24) NULL,
    ADD COLUMN requested_service_tier VARCHAR(24) NULL,
    ADD COLUMN actual_service_tier VARCHAR(24) NULL,
    ADD COLUMN cached_input_tokens INT NULL,
    ADD COLUMN reasoning_tokens INT NULL,
    ADD COLUMN cost_pricing_tier VARCHAR(24) NULL,
    ADD COLUMN cost_calculation_status VARCHAR(32) NULL,
    ADD COLUMN cached_input_unit_price DECIMAL(18,6) NULL;

ALTER TABLE llm_request
    MODIFY COLUMN estimated_cost DECIMAL(24,12) NULL;
