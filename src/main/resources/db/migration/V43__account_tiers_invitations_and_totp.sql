ALTER TABLE app_user
    ADD COLUMN account_tier VARCHAR(32) NOT NULL DEFAULT 'GENERAL_USER',
    ADD COLUMN invited_by_user_id CHAR(36) NULL,
    ADD COLUMN totp_secret_encrypted TEXT NULL,
    ADD COLUMN totp_pending_secret_encrypted TEXT NULL,
    ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN totp_enrolled_at TIMESTAMP(6) NULL,
    ADD COLUMN totp_last_used_step BIGINT NULL,
    ADD COLUMN totp_failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN totp_locked_until TIMESTAMP(6) NULL;

UPDATE app_user SET account_tier = 'SYSTEM_ADMIN' WHERE platform_admin = TRUE;
UPDATE app_user SET account_tier = 'PROJECT_DIRECTOR'
WHERE platform_admin = FALSE
  AND id IN (SELECT user_id FROM organization_member WHERE role = 'ORGANIZATION_ADMIN');

ALTER TABLE auth_refresh_token
    ADD COLUMN mfa_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE account_invitation (
    id CHAR(36) NOT NULL PRIMARY KEY,
    token_hash CHAR(64) NOT NULL UNIQUE,
    code_prefix VARCHAR(16) NOT NULL,
    target_tier VARCHAR(32) NOT NULL,
    organization_id CHAR(36) NOT NULL,
    team_id CHAR(36) NULL,
    team_role VARCHAR(32) NULL,
    project_id CHAR(36) NULL,
    beta_service_id CHAR(36) NULL,
    expected_email VARCHAR(320) NULL,
    issued_by_user_id CHAR(36) NOT NULL,
    issued_by_tier VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6) NULL,
    consumed_by_user_id CHAR(36) NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_account_invitation_org (organization_id, created_at),
    INDEX idx_account_invitation_issuer (issued_by_user_id, created_at)
);

CREATE TABLE user_totp_recovery_code (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    used_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_totp_recovery_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    INDEX idx_totp_recovery_user (user_id)
);
