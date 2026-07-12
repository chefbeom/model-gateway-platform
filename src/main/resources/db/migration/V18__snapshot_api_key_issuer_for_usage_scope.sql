ALTER TABLE llm_request
    ADD COLUMN api_key_issuer_user_id CHAR(36) NULL AFTER api_key_id;

UPDATE llm_request AS request_row
SET api_key_issuer_user_id = (
    SELECT key_row.issued_by_user_id
    FROM api_key AS key_row
    WHERE key_row.id = request_row.api_key_id
)
WHERE request_row.api_key_issuer_user_id IS NULL
  AND request_row.api_key_id IS NOT NULL;

CREATE INDEX idx_llm_request_issuer_started
    ON llm_request(api_key_issuer_user_id, started_at);
