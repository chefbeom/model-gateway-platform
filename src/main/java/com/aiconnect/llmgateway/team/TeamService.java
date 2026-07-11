package com.aiconnect.llmgateway.team;

import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.identity.OrganizationMemberRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TeamService {
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository organizationMembers;
    private final AuditService audit;

    public TeamService(TeamRepository teams, TeamMemberRepository members, OrganizationRepository organizations,
                       OrganizationMemberRepository organizationMembers, AuditService audit) {
        this.teams = teams;
        this.members = members;
        this.organizations = organizations;
        this.organizationMembers = organizationMembers;
        this.audit = audit;
    }

    @Transactional
    public Team create(UUID organizationId, String name) {
        requireOrganization(organizationId);
        Team created = teams.save(new Team(organizationId, name));
        audit.record(organizationId, CurrentActor.userIdOrNull(), "TEAM_CREATED", "TEAM", created.getId(), Map.of("name", name));
        return created;
    }

    @Transactional
    public TeamMember grantMembership(UUID organizationId, UUID teamId, UUID userId, TeamRole role) {
        Team team = requireTeam(organizationId, teamId);
        if (organizationMembers.findByIdOrganizationIdAndIdUserId(organizationId, userId).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEAM_MEMBER_REQUIRES_ORGANIZATION_MEMBERSHIP",
                    "A team member must first belong to the organization.");
        }
        TeamMember member = members.save(new TeamMember(team.getId(), userId, role));
        audit.record(organizationId, CurrentActor.userIdOrNull(), "TEAM_MEMBERSHIP_GRANTED", "TEAM_MEMBER", userId,
                Map.of("teamId", teamId.toString(), "role", role.name()));
        return member;
    }

    @Transactional(readOnly = true)
    public List<Team> list(UUID organizationId) {
        requireOrganization(organizationId);
        return teams.findByOrganizationIdOrderByNameAsc(organizationId);
    }

    @Transactional(readOnly = true)
    public List<TeamMember> members(UUID organizationId, UUID teamId) {
        requireTeam(organizationId, teamId);
        return members.findByIdTeamId(teamId);
    }

    @Transactional(readOnly = true)
    public Team requireTeam(UUID organizationId, UUID teamId) {
        Team team = teams.findById(teamId).orElseThrow(() -> notFound("TEAM_NOT_FOUND", "The team does not exist."));
        if (!team.getOrganizationId().equals(organizationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "The team belongs to another organization.");
        }
        return team;
    }

    private void requireOrganization(UUID organizationId) {
        if (!organizations.existsById(organizationId)) throw notFound("ORGANIZATION_NOT_FOUND", "The organization does not exist.");
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }
}
