CREATE TABLE accelerator_device (
    id CHAR(36) NOT NULL PRIMARY KEY,
    node_id CHAR(36) NOT NULL,
    vendor VARCHAR(80) NULL,
    product_name VARCHAR(240) NULL,
    device_index INT NOT NULL,
    device_uuid VARCHAR(160) NULL,
    memory_total_mb INT NULL,
    driver_version VARCHAR(120) NULL,
    metadata_json TEXT NULL,
    last_seen_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_accelerator_device_node FOREIGN KEY (node_id) REFERENCES inference_node(id),
    CONSTRAINT uk_accelerator_node_index UNIQUE (node_id, device_index)
);
