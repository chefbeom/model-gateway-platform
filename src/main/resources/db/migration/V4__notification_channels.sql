CREATE TABLE notification_channel (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    encrypted_target TEXT NOT NULL,
    encrypted_secret TEXT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_notification_channel_organization FOREIGN KEY (organization_id) REFERENCES organization(id)
);

CREATE TABLE notification_delivery (
    id CHAR(36) NOT NULL PRIMARY KEY,
    incident_id CHAR(36) NOT NULL,
    notification_channel_id CHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_notification_delivery_incident FOREIGN KEY (incident_id) REFERENCES incident(id),
    CONSTRAINT fk_notification_delivery_channel FOREIGN KEY (notification_channel_id) REFERENCES notification_channel(id),
    INDEX idx_notification_delivery_incident (incident_id)
);
