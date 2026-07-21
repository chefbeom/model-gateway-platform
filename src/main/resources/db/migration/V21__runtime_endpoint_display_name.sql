ALTER TABLE runtime_endpoint
    ADD COLUMN display_name VARCHAR(160) NOT NULL DEFAULT 'LM Studio Runtime' AFTER node_id;
