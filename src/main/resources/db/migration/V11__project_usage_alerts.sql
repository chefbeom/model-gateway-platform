CREATE TABLE project_alert_policy (
    project_id CHAR(36) NOT NULL PRIMARY KEY,
    requests_per_minute_threshold INT NULL,
    error_rate_percent_threshold DECIMAL(5,2) NULL,
    monthly_token_usage_percent_threshold INT NULL,
    cooldown_seconds INT NOT NULL DEFAULT 900,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_alert_policy_project FOREIGN KEY (project_id) REFERENCES project(id)
);

CREATE TABLE usage_alert_delivery (
    id CHAR(36) NOT NULL PRIMARY KEY,
    project_id CHAR(36) NOT NULL,
    notification_channel_id CHAR(36) NOT NULL,
    metric VARCHAR(48) NOT NULL,
    observed_value DECIMAL(18,4) NOT NULL,
    threshold_value DECIMAL(18,4) NOT NULL,
    status VARCHAR(24) NOT NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_usage_alert_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_usage_alert_channel FOREIGN KEY (notification_channel_id) REFERENCES notification_channel(id),
    INDEX idx_usage_alert_project_created (project_id, created_at)
);

CREATE TABLE usage_alert_state (
    project_id CHAR(36) NOT NULL,
    metric VARCHAR(48) NOT NULL,
    last_sent_at TIMESTAMP(6) NULL,
    PRIMARY KEY (project_id, metric),
    CONSTRAINT fk_usage_alert_state_project FOREIGN KEY (project_id) REFERENCES project(id)
);
