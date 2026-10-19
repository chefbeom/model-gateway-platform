ALTER TABLE runtime_endpoint ADD COLUMN max_concurrent_requests INT NOT NULL DEFAULT 1;
CREATE TABLE service_request_scheduling (
    service_id CHAR(36) NOT NULL PRIMARY KEY,
    settings_json TEXT NOT NULL,
    CONSTRAINT fk_scheduling_service FOREIGN KEY (service_id) REFERENCES llm_service(id) ON DELETE CASCADE
);
CREATE TABLE request_scheduling_trace (
    request_id VARCHAR(120) NOT NULL PRIMARY KEY,
    service_id CHAR(36) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    phase VARCHAR(24) NOT NULL,
    queued_at TIMESTAMP(6) NULL,
    dispatched_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    deadline TIMESTAMP(6) NULL,
    queue_wait_ms BIGINT NOT NULL DEFAULT 0,
    execution_ms BIGINT NULL,
    dispatch_reason VARCHAR(64) NULL,
    error_code VARCHAR(80) NULL,
    deployment_id CHAR(36) NULL
);
CREATE INDEX idx_scheduling_trace_service ON request_scheduling_trace(service_id, queued_at);
CREATE INDEX idx_scheduling_trace_expiry ON request_scheduling_trace(phase, deadline);
