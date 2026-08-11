CREATE TABLE spend_quota_reservation (
    id CHAR(36) NOT NULL PRIMARY KEY,
    quota_id CHAR(36) NOT NULL,
    reservation_key CHAR(36) NOT NULL,
    amount DECIMAL(18,6) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_spend_quota_reservation_quota FOREIGN KEY (quota_id) REFERENCES spend_quota(id) ON DELETE CASCADE,
    CONSTRAINT ck_spend_quota_reservation_amount_nonnegative CHECK (amount >= 0),
    INDEX idx_spend_quota_reservation_quota_expiry (quota_id, expires_at),
    INDEX idx_spend_quota_reservation_key (reservation_key)
);
