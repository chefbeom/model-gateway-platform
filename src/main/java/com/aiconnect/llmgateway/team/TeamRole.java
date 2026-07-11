package com.aiconnect.llmgateway.team;

public enum TeamRole {
    TEAM_ADMIN,
    PROJECT_OWNER,
    DEVELOPER,
    AUDITOR;

    public boolean canManageProjects() { return this == TEAM_ADMIN || this == PROJECT_OWNER; }
    public boolean canManageMembers() { return this == TEAM_ADMIN; }
    public boolean canViewRequests() { return true; }
    public boolean canReadSensitiveContent() { return this == TEAM_ADMIN || this == PROJECT_OWNER || this == AUDITOR; }
}
