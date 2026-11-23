CREATE TABLE service_flow_layout (
    service_id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    layout_json TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_by CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_service_flow_layout_service FOREIGN KEY (service_id) REFERENCES llm_service(id) ON DELETE CASCADE
);
CREATE INDEX idx_service_flow_layout_org ON service_flow_layout(organization_id);
