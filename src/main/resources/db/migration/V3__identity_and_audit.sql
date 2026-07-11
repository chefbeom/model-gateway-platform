CREATE TABLE app_user (
    id CHAR(36) NOT NULL PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    platform_admin BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_app_user_email UNIQUE (email)
);

CREATE TABLE organization_member (
    organization_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (organization_id, user_id),
    CONSTRAINT fk_member_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);

CREATE TABLE auth_refresh_token (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    INDEX idx_refresh_token_user (user_id)
);

CREATE TABLE audit_log (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NULL,
    actor_user_id CHAR(36) NULL,
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(80) NULL,
    detail_json TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_audit_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT fk_audit_user FOREIGN KEY (actor_user_id) REFERENCES app_user(id),
    INDEX idx_audit_organization_created (organization_id, created_at),
    INDEX idx_audit_actor_created (actor_user_id, created_at)
);
