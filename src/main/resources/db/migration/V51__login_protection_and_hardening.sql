ALTER TABLE app_user
    ADD COLUMN login_failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN login_locked_until TIMESTAMP(6) NULL;
