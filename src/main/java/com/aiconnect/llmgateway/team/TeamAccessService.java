package com.aiconnect.llmgateway.team;

import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.OrganizationMember;
import com.aiconnect.llmgateway.identity.OrganizationMemberRepository;
import com.aiconnect.llmgateway.identity.OrganizationRole;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class TeamAccessService {
    private final OrganizationMemberRepository organizationMembers;
    private final TeamMemberRepository teamMembers;
    private final ProjectRepository projects;

    public TeamAccessService(OrganizationMemberRepository organizationMembers, TeamMemberRepository teamMembers,
                             ProjectRepository projects) {
        this.organizationMembers = organizationMembers;
        this.teamMembers = teamMembers;
        this.projects = projects;
    }

    public boolean canViewOrganization(AuthPrincipal actor, UUID organizationId) {
        return actor != null && (actor.platformAdmin() || organizationMembers.findByIdOrganizationIdAndIdUserId(organizationId, actor.userId()).isPresent());
    }

    public boolean isOrganizationAdmin(AuthPrincipal actor, UUID organizationId) {
        return actor != null && (actor.platformAdmin() || organizationMembers.findByIdOrganizationIdAndIdUserId(organizationId, actor.userId())
                .map(OrganizationMember::getRole).filter(role -> role == OrganizationRole.ORGANIZATION_ADMIN).isPresent());
    }

    public boolean canManageTeam(AuthPrincipal actor, UUID teamId) { return teamRole(actor, teamId).map(TeamRole::canManageMembers).orElse(false); }
    public boolean canCreateProject(AuthPrincipal actor, UUID organizationId, UUID teamId) {
        return isOrganizationAdmin(actor, organizationId) || (teamId != null && teamRole(actor, teamId).map(TeamRole::canManageProjects).orElse(false));
    }

    public boolean canManageProject(AuthPrincipal actor, UUID projectId) {
        Project project = projects.findById(projectId).orElse(null);
        return project != null && (isOrganizationAdmin(actor, project.getOrganizationId())
                || (project.getTeamId() != null && teamRole(actor, project.getTeamId()).map(TeamRole::canManageProjects).orElse(false)));
    }

    public boolean canViewProject(AuthPrincipal actor, UUID projectId) {
        Project project = projects.findById(projectId).orElse(null);
        if (project == null || !canViewOrganization(actor, project.getOrganizationId())) return false;
        return isOrganizationAdmin(actor, project.getOrganizationId())
                || (project.getTeamId() != null && teamRole(actor, project.getTeamId()).map(TeamRole::canViewRequests).orElse(false));
    }

    public boolean canReadSensitiveContent(AuthPrincipal actor, UUID projectId) {
        Project project = projects.findById(projectId).orElse(null);
        if (project == null || !canViewOrganization(actor, project.getOrganizationId())) return false;
        return isOrganizationAdmin(actor, project.getOrganizationId())
                || (project.getTeamId() != null && teamRole(actor, project.getTeamId()).map(TeamRole::canReadSensitiveContent).orElse(false));
    }

    public Optional<TeamRole> teamRole(AuthPrincipal actor, UUID teamId) {
        if (actor == null || teamId == null) return Optional.empty();
        return teamMembers.findByIdTeamIdAndIdUserId(teamId, actor.userId()).map(TeamMember::getRole);
    }
}
