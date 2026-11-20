ALTER TABLE app_user ADD COLUMN project_scope_enforced BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE app_user SET project_scope_enforced = TRUE
WHERE id IN (SELECT user_id FROM project_membership);
