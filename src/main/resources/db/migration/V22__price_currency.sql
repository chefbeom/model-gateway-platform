ALTER TABLE llm_service
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'KRW' AFTER output_price_per_million;

ALTER TABLE model_deployment
    ADD COLUMN provider_price_currency VARCHAR(3) NULL DEFAULT 'KRW' AFTER provider_output_price_per_million;

ALTER TABLE llm_request
    ADD COLUMN cost_currency VARCHAR(3) NOT NULL DEFAULT 'KRW' AFTER estimated_cost;
