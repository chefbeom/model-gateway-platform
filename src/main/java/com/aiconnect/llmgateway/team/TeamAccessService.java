package com.aiconnect.llmgateway.team;

import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.OrganizationMember;
import com.aiconnect.llmgateway.identity.OrganizationMemberRepository;
import com.aiconnect.llmgateway.identity.OrganizationRole;
import com.aiconnect.llmgateway.identity.AccountTier;
import com.aiconnect.llmgateway.identity.ProjectMembership;
import com.aiconnect.llmgateway.identity.ProjectMembershipId;
import com.aiconnect.llmgateway.identity.ProjectMembershipRepository;
import com.aiconnect.llmgateway.identity.AppUserRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class TeamAccessService {
    private final OrganizationMemberRepository organizationMembers;
    private final TeamMemberRepository teamMembers;
    private final ProjectRepository projects;
    private final ProjectMembershipRepository projectMemberships;
    private final AppUserRepository users;
    @Autowired(required = false)
    private OrganizationRepository organizations;

    public TeamAccessService(OrganizationMemberRepository organizationMembers, TeamMemberRepository teamMembers,
                             ProjectRepository projects, ProjectMembershipRepository projectMemberships, AppUserRepository users) {
        this.organizationMembers = organizationMembers;
        this.teamMembers = teamMembers;
        this.projects = projects;
        this.projectMemberships = projectMemberships;
        this.users = users;
    }

    public boolean canViewOrganization(AuthPrincipal actor, UUID organizationId) {
        if (actor == null) return false;
        if (actor.platformAdmin()) return true;
        if (organizations != null && organizationId != null) {
            Organization org = organizations.findById(organizationId).orElse(null);
            if (org != null && !"ACTIVE".equalsIgnoreCase(org.getStatus())) return false;
        }
        return organizationMembers.findByIdOrganizationIdAndIdUserId(organizationId, actor.userId()).isPresent();
    }

    public boolean isOrganizationAdmin(AuthPrincipal actor, UUID organizationId) {
        // PROJECT_DIRECTOR is not an organization-wide administrator even if
        // a legacy invitation assigned that database role.
        if (isDirector(actor)) return false;
        return actor != null && (actor.platformAdmin() || organizationMembers.findByIdOrganizationIdAndIdUserId(organizationId, actor.userId())
                .map(OrganizationMember::getRole).filter(role -> role == OrganizationRole.ORGANIZATION_ADMIN).isPresent());
    }

    public boolean canManageTeam(AuthPrincipal actor, UUID teamId) { return !isDirector(actor) && !hasExplicitProjectScope(actor) && teamRole(actor, teamId).map(TeamRole::canManageMembers).orElse(false); }
    public boolean canCreateProject(AuthPrincipal actor, UUID organizationId, UUID teamId) {
        if (isDirector(actor) || hasExplicitProjectScope(actor)) return false;
        return isOrganizationAdmin(actor, organizationId) || (teamId != null && teamRole(actor, teamId).map(TeamRole::canManageProjects).orElse(false));
    }

    public boolean canManageProject(AuthPrincipal actor, UUID projectId) {
        Project project = projects.findById(projectId).orElse(null);
        if (isDirector(actor)) return project != null && canViewOrganization(actor, project.getOrganizationId())
                && projectRole(actor, projectId).filter(role -> role == ProjectMembership.Role.MANAGER).isPresent();
        if (project != null && hasExplicitProjectScope(actor) && !isOrganizationAdmin(actor, project.getOrganizationId()))
            return canViewOrganization(actor, project.getOrganizationId()) && projectRole(actor, projectId)
                    .filter(role -> role == ProjectMembership.Role.MANAGER).isPresent();
        return project != null && (isOrganizationAdmin(actor, project.getOrganizationId())
                || (project.getTeamId() != null && teamRole(actor, project.getTeamId()).map(TeamRole::canManageProjects).orElse(false)));
    }

    public boolean canViewProject(AuthPrincipal actor, UUID projectId) {
        Project project = projects.findById(projectId).orElse(null);
        if (project == null || !canViewOrganization(actor, project.getOrganizationId())) return false;
        if (isDirector(actor)) return projectRole(actor, projectId).isPresent();
        if (hasExplicitProjectScope(actor) && !isOrganizationAdmin(actor, project.getOrganizationId())) return projectRole(actor, projectId).isPresent();
        return isOrganizationAdmin(actor, project.getOrganizationId())
                || (project.getTeamId() != null && teamRole(actor, project.getTeamId()).map(TeamRole::canViewRequests).orElse(false));
    }

    public boolean canReadSensitiveContent(AuthPrincipal actor, UUID projectId) {
        Project project = projects.findById(projectId).orElse(null);
        if (project == null || !canViewOrganization(actor, project.getOrganizationId())) return false;
        if (isDirector(actor)) return projectRole(actor, projectId).filter(role -> role == ProjectMembership.Role.MANAGER).isPresent();
        if (hasExplicitProjectScope(actor) && !isOrganizationAdmin(actor, project.getOrganizationId()))
            return projectRole(actor, projectId).filter(role -> role == ProjectMembership.Role.MANAGER).isPresent();
        return isOrganizationAdmin(actor, project.getOrganizationId())
                || (project.getTeamId() != null && teamRole(actor, project.getTeamId()).map(TeamRole::canReadSensitiveContent).orElse(false));
    }

    public boolean canIssueProjectApiKeys(AuthPrincipal actor, UUID projectId) {
        if (actor == null || projectId == null) return false;
        Project project = projects.findById(projectId).orElse(null);
        if (project == null || !canViewOrganization(actor, project.getOrganizationId())) return false;
        if (actor.platformAdmin() || isOrganizationAdmin(actor, project.getOrganizationId()) || canManageProject(actor, projectId)) {
            return true;
        }
        Optional<ProjectMembership> membership = projectMemberships.findById(new ProjectMembershipId(projectId, actor.userId()));
        if (membership.isPresent()) {
            return membership.get().isCanIssueApiKeys();
        }
        if (hasExplicitProjectScope(actor) || actor.accountTier() == AccountTier.GENERAL_USER) {
            return false;
        }
        return canViewProject(actor, projectId);
    }

    public Optional<TeamRole> teamRole(AuthPrincipal actor, UUID teamId) {
        if (actor == null || teamId == null) return Optional.empty();
        return teamMembers.findByIdTeamIdAndIdUserId(teamId, actor.userId()).map(TeamMember::getRole);
    }

    private boolean isDirector(AuthPrincipal actor) {
        return actor != null && !actor.platformAdmin() && actor.accountTier() == AccountTier.PROJECT_DIRECTOR;
    }

    private Optional<ProjectMembership.Role> projectRole(AuthPrincipal actor, UUID projectId) {
        if (actor == null || projectId == null) return Optional.empty();
        return projectMemberships.findById(new ProjectMembershipId(projectId, actor.userId())).map(ProjectMembership::getRole);
    }

    public boolean hasExplicitProjectScope(AuthPrincipal actor) {
        return actor != null && !actor.platformAdmin() && users.findById(actor.userId())
                .map(user -> user.isProjectScopeEnforced()).orElse(true);
    }
}
