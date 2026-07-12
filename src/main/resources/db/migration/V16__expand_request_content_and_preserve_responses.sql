ALTER TABLE llm_request_content
    MODIFY encrypted_request MEDIUMTEXT NOT NULL,
    MODIFY encrypted_response MEDIUMTEXT NULL;
