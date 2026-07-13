INSERT INTO organization (id, name, status, created_at, updated_at)
SELECT UUID(), 'Default Workspace', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (SELECT 1 FROM organization);

INSERT INTO organization_member (organization_id, user_id, role, created_at)
SELECT organization.id, app_user.id, 'ORGANIZATION_ADMIN', CURRENT_TIMESTAMP(6)
FROM organization
JOIN app_user ON app_user.platform_admin = TRUE
WHERE organization.name = 'Default Workspace'
  AND NOT EXISTS (
      SELECT 1
      FROM organization_member
      WHERE organization_member.organization_id = organization.id
        AND organization_member.user_id = app_user.id
  );
