ALTER TABLE runtime_endpoint
    ADD COLUMN input_price_per_million DECIMAL(18,6) NULL;

ALTER TABLE runtime_endpoint
    ADD COLUMN output_price_per_million DECIMAL(18,6) NULL;

ALTER TABLE runtime_endpoint
    ADD COLUMN currency VARCHAR(3) NULL;
