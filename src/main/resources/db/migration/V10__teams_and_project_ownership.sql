CREATE TABLE team (
    id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_team_organization FOREIGN KEY (organization_id) REFERENCES organization(id),
    CONSTRAINT uk_team_organization_name UNIQUE (organization_id, name)
);

CREATE TABLE team_member (
    team_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (team_id, user_id),
    CONSTRAINT fk_team_member_team FOREIGN KEY (team_id) REFERENCES team(id),
    CONSTRAINT fk_team_member_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);

ALTER TABLE project ADD COLUMN team_id CHAR(36) NULL AFTER organization_id;
ALTER TABLE project ADD CONSTRAINT fk_project_team FOREIGN KEY (team_id) REFERENCES team(id);
CREATE INDEX idx_project_team ON project(team_id);
