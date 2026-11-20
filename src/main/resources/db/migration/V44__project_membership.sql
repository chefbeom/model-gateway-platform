CREATE TABLE project_membership (
    project_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    role VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT fk_project_membership_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
    CONSTRAINT fk_project_membership_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    INDEX idx_project_membership_user (user_id)
);

-- Only explicit, accepted project invitations establish a scope; team or
-- organization-wide roles are not safe evidence of project ownership.
INSERT INTO project_membership (project_id, user_id, role, created_at)
SELECT DISTINCT i.project_id, i.consumed_by_user_id,
       CASE WHEN i.target_tier = 'PROJECT_DIRECTOR' THEN 'MANAGER' ELSE 'MEMBER' END,
       CURRENT_TIMESTAMP(6)
FROM account_invitation i
JOIN project p ON p.id = i.project_id AND p.organization_id = i.organization_id
JOIN app_user u ON u.id = i.consumed_by_user_id
WHERE i.project_id IS NOT NULL AND i.consumed_at IS NOT NULL
  AND i.consumed_by_user_id IS NOT NULL;
