ALTER TABLE llm_request
    MODIFY api_key_id CHAR(36) NULL;

ALTER TABLE llm_request
    DROP FOREIGN KEY fk_request_api_key;

ALTER TABLE llm_request
    ADD CONSTRAINT fk_request_api_key
        FOREIGN KEY (api_key_id) REFERENCES api_key(id) ON DELETE SET NULL;
