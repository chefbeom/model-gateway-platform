ALTER TABLE project
    ADD COLUMN data_boundary_policy VARCHAR(40) NOT NULL DEFAULT 'EXTERNAL_ALLOWED',
    ADD COLUMN max_data_boundary_policy VARCHAR(40) NOT NULL DEFAULT 'EXTERNAL_ALLOWED';
UPDATE project
SET data_boundary_policy = 'INTERNAL_ONLY',
    max_data_boundary_policy = 'INTERNAL_ONLY'
WHERE external_ai_blocked = TRUE;

ALTER TABLE llm_service
    ADD COLUMN data_boundary_policy VARCHAR(40) NOT NULL DEFAULT 'EXTERNAL_ALLOWED';

ALTER TABLE api_key
    ADD COLUMN data_boundary_policy VARCHAR(40) NOT NULL DEFAULT 'EXTERNAL_ALLOWED';

ALTER TABLE project_membership
    ADD COLUMN can_issue_api_keys BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE project_membership
SET can_issue_api_keys = TRUE
WHERE role = 'MANAGER';

ALTER TABLE account_invitation
    ADD COLUMN can_issue_api_keys BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE runtime_trust_attestation
    ADD COLUMN vendor_name VARCHAR(120) NULL,
    ADD COLUMN processing_location VARCHAR(120) NULL,
    ADD COLUMN retention_policy VARCHAR(240) NULL,
    ADD COLUMN approval_scope VARCHAR(240) NULL;
