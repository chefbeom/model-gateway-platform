-- V36 is already deployed; new features must migrate after that version.
CREATE TABLE playground_conversation (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NULL,
    target_id CHAR(36) NOT NULL,
    target_name VARCHAR(160) NOT NULL,
    model_id VARCHAR(500) NOT NULL,
    encrypted_transcript MEDIUMTEXT NOT NULL,
    message_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    INDEX idx_playground_conversation_actor_updated (organization_id, actor_user_id, updated_at),
    INDEX idx_playground_conversation_target (target_id, updated_at)
);
