package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.beta.BetaOperator;
import com.aiconnect.llmgateway.beta.BetaOperatorRepository;
import com.aiconnect.llmgateway.beta.BetaService;
import com.aiconnect.llmgateway.beta.BetaServiceRepository;
import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.team.TeamMember;
import com.aiconnect.llmgateway.team.TeamMemberRepository;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.team.TeamRole;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles organization offboarding without deleting request or usage history.
 * API key records are intentionally removed only after a preview is returned to
 * the administrator; the request rows keep their historical relationship as null.
 */
@Service
public class OrganizationAccessService {
    private final OrganizationRepository organizations;
    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;
    private final OrganizationMemberRepository organizationMembers;
    private final ProjectRepository projects;
    private final ApiKeyRepository apiKeys;
    private final AppUserRepository users;
    private final AuditService audit;
    @Autowired(required = false)
    private ProjectMembershipRepository projectMemberships;
    @Autowired(required = false)
    private AccountInvitationRepository invitations;
    @Autowired(required = false)
    private BetaServiceRepository betaServices;
    @Autowired(required = false)
    private BetaOperatorRepository betaOperators;
    @Autowired(required = false)
    private AuthRefreshTokenRepository refreshTokens;

    public OrganizationAccessService(OrganizationRepository organizations, TeamRepository teams,
                                     TeamMemberRepository teamMembers, OrganizationMemberRepository organizationMembers,
                                     ProjectRepository projects, ApiKeyRepository apiKeys, AppUserRepository users,
                                     AuditService audit) {
        this.organizations = organizations;
        this.teams = teams;
        this.teamMembers = teamMembers;
        this.organizationMembers = organizationMembers;
        this.projects = projects;
        this.apiKeys = apiKeys;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<OrganizationUserView> organizationUsers(UUID organizationId) {
        requireOrganization(organizationId);
        return organizationMembers.findByIdOrganizationId(organizationId).stream()
                .map(member -> {
                    AppUser user = users.findById(member.getId().getUserId()).orElse(null);
                    String userEmail = user != null ? user.getEmail() : "삭제된 사용자";
                    String tier = user != null ? AccountTier.effectiveFor(user).name() : AccountTier.GENERAL_USER.name();
                    boolean totpEnabled = user != null && user.isTotpEnabled();
                    return new OrganizationUserView(member.getId().getUserId(), userEmail, member.getRole(), tier, totpEnabled);
                })
                .sorted(Comparator.comparing(OrganizationUserView::email, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamDeletionPreview teamDeletionPreview(UUID organizationId, UUID teamId) {
        Team team = requireTeam(organizationId, teamId);
        List<Project> teamProjects = projects.findByTeamId(teamId);
        List<ApiKey> teamKeys = keysForProjects(teamProjects);
        List<TeamMemberReference> members = teamMembers.findByIdTeamId(teamId).stream()
                .map(member -> new TeamMemberReference(member.getId().getUserId(), email(member.getId().getUserId()), member.getRole()))
                .sorted(Comparator.comparing(TeamMemberReference::email, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new TeamDeletionPreview(
                team.getId(), team.getName(), members,
                projectReferences(teamProjects, teamKeys), keyReferences(teamKeys, mapProjects(teamProjects)),
                "프로젝트는 조직 공용으로 전환되고 SUSPENDED 상태가 됩니다. 요청·사용량 이력은 보존됩니다.");
    }

    @Transactional
    public void deleteTeam(UUID organizationId, UUID teamId) {
        Team team = requireTeam(organizationId, teamId);
        List<Project> teamProjects = projects.findByTeamId(teamId);
        List<ApiKey> affectedKeys = keysForProjects(teamProjects);
        List<TeamMember> affectedMembers = teamMembers.findByIdTeamId(teamId);

        apiKeys.deleteAll(affectedKeys);
        apiKeys.flush();

        teamProjects.forEach(Project::unassignTeamAndSuspend);
        projects.saveAll(teamProjects);
        projects.flush();

        teamMembers.deleteAll(affectedMembers);
        teamMembers.flush();
        teams.delete(team);
        teams.flush();

        audit.record(organizationId, CurrentActor.userIdOrNull(), "TEAM_DELETED", "TEAM", teamId,
                Map.of("name", team.getName(), "suspendedProjectCount", teamProjects.size(),
                        "deletedApiKeyCount", affectedKeys.size(), "removedTeamMemberCount", affectedMembers.size()));
    }

    @Transactional
    public void removeTeamMember(UUID organizationId, UUID teamId, UUID userId) {
        requireTeam(organizationId, teamId);
        TeamMember member = teamMembers.findByIdTeamIdAndIdUserId(teamId, userId)
                .orElseThrow(() -> notFound("TEAM_MEMBER_NOT_FOUND", "The user is not assigned to this team."));
        teamMembers.delete(member);
        audit.record(organizationId, CurrentActor.userIdOrNull(), "TEAM_MEMBER_REMOVED", "TEAM_MEMBER", userId,
                Map.of("teamId", teamId.toString()));
    }

    @Transactional(readOnly = true)
    public UserRemovalPreview userRemovalPreview(UUID organizationId, UUID userId) {
        OrganizationMember organizationMember = requireOrganizationMember(organizationId, userId);
        List<TeamMembershipReference> teamMemberships = membershipsInOrganization(organizationId, userId);
        List<ApiKey> keys = keysIssuedInOrganization(organizationId, userId);
        return new UserRemovalPreview(userId, email(userId), organizationMember.getRole(), teamMemberships,
                keyReferences(keys, projectsForOrganization(organizationId)),
                "조직 계정 권한과 이 조직의 팀 배정만 제거합니다. 다른 조직 소속과 로그인 계정 자체는 유지됩니다.");
    }

    @Transactional
    public void removeOrganizationUser(UUID organizationId, UUID userId) {
        OrganizationMember membership = requireOrganizationMember(organizationId, userId);
        if (membership.getRole() == OrganizationRole.ORGANIZATION_ADMIN
                && organizationMembers.countByIdOrganizationIdAndRole(organizationId, OrganizationRole.ORGANIZATION_ADMIN) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ORGANIZATION_ADMIN",
                    "At least one organization administrator must remain. Assign another administrator first.");
        }

        List<TeamMember> memberships = teamMembershipsInOrganization(organizationId, userId);
        List<ApiKey> keys = keysIssuedInOrganization(organizationId, userId);
        Map<UUID, Project> orgProjects = projectsForOrganization(organizationId);
        List<ProjectMembership> orgProjectMemberships = projectMemberships == null ? List.of()
                : projectMemberships.findByIdUserId(userId).stream()
                .filter(pm -> orgProjects.containsKey(pm.getId().getProjectId()))
                .toList();

        apiKeys.deleteAll(keys);
        apiKeys.flush();
        if (projectMemberships != null && !orgProjectMemberships.isEmpty()) {
            projectMemberships.deleteAll(orgProjectMemberships);
            projectMemberships.flush();
        }
        teamMembers.deleteAll(memberships);
        teamMembers.flush();
        organizationMembers.delete(membership);
        organizationMembers.flush();

        int revokedInvitations = 0;
        if (invitations != null) {
            Instant now = Instant.now();
            List<AccountInvitation> pending = invitations
                    .findByOrganizationIdAndIssuedByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(organizationId, userId);
            for (AccountInvitation invite : pending) {
                invite.revoke(now);
            }
            if (!pending.isEmpty()) {
                invitations.saveAll(pending);
                revokedInvitations = pending.size();
            }
        }

        if (betaServices != null && betaOperators != null) {
            for (BetaService service : betaServices.findByOrganizationId(organizationId)) {
                BetaOperator.Key opKey = new BetaOperator.Key(service.getId(), userId);
                if (betaOperators.existsById(opKey)) {
                    betaOperators.deleteById(opKey);
                }
            }
        }

        if (refreshTokens != null) {
            boolean hasOtherOrgs = !organizationMembers.findByIdUserId(userId).isEmpty();
            boolean isPlatformAdmin = users.findById(userId).map(AppUser::isPlatformAdmin).orElse(false);
            if (!hasOtherOrgs && !isPlatformAdmin) {
                List<AuthRefreshToken> activeTokens = refreshTokens.findByUserIdAndRevokedAtIsNull(userId);
                if (!activeTokens.isEmpty()) {
                    activeTokens.forEach(AuthRefreshToken::revoke);
                    refreshTokens.saveAll(activeTokens);
                }
            }
        }

        audit.record(organizationId, CurrentActor.userIdOrNull(), "ORGANIZATION_USER_REMOVED", "APP_USER", userId,
                Map.of("email", email(userId), "deletedApiKeyCount", keys.size(),
                        "removedTeamMembershipCount", memberships.size(),
                        "removedProjectMembershipCount", orgProjectMemberships.size(),
                        "revokedInvitationCount", revokedInvitations));
    }

    private Team requireTeam(UUID organizationId, UUID teamId) {
        Team team = teams.findById(teamId).orElseThrow(() -> notFound("TEAM_NOT_FOUND", "The team does not exist."));
        if (!team.getOrganizationId().equals(organizationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "The team belongs to another organization.");
        }
        return team;
    }

    private OrganizationMember requireOrganizationMember(UUID organizationId, UUID userId) {
        requireOrganization(organizationId);
        return organizationMembers.findByIdOrganizationIdAndIdUserId(organizationId, userId)
                .orElseThrow(() -> notFound("ORGANIZATION_MEMBER_NOT_FOUND", "The user is not a member of this organization."));
    }

    private void requireOrganization(UUID organizationId) {
        if (!organizations.existsById(organizationId)) throw notFound("ORGANIZATION_NOT_FOUND", "The organization does not exist.");
    }

    private List<ApiKey> keysForProjects(List<Project> scopedProjects) {
        if (scopedProjects.isEmpty()) return List.of();
        return apiKeys.findByProjectIdIn(scopedProjects.stream().map(Project::getId).toList());
    }

    private List<ApiKey> keysIssuedInOrganization(UUID organizationId, UUID userId) {
        Map<UUID, Project> organizationProjects = projectsForOrganization(organizationId);
        return apiKeys.findByIssuedByUserId(userId).stream()
                .filter(key -> organizationProjects.containsKey(key.getProjectId()))
                .toList();
    }

    private List<TeamMember> teamMembershipsInOrganization(UUID organizationId, UUID userId) {
        return teamMembers.findByIdUserId(userId).stream()
                .filter(member -> teams.findById(member.getId().getTeamId())
                        .map(team -> organizationId.equals(team.getOrganizationId())).orElse(false))
                .toList();
    }

    private List<TeamMembershipReference> membershipsInOrganization(UUID organizationId, UUID userId) {
        return teamMembershipsInOrganization(organizationId, userId).stream()
                .map(member -> teams.findById(member.getId().getTeamId())
                        .map(team -> new TeamMembershipReference(team.getId(), team.getName(), member.getRole()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(TeamMembershipReference::teamName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private Map<UUID, Project> projectsForOrganization(UUID organizationId) {
        return mapProjects(projects.findByOrganizationId(organizationId));
    }

    private Map<UUID, Project> mapProjects(Collection<Project> source) {
        Map<UUID, Project> result = new HashMap<>();
        source.forEach(project -> result.put(project.getId(), project));
        return result;
    }

    private List<ProjectReference> projectReferences(List<Project> source, List<ApiKey> keys) {
        Map<UUID, Long> counts = new HashMap<>();
        keys.forEach(key -> counts.merge(key.getProjectId(), 1L, Long::sum));
        return source.stream()
                .map(project -> new ProjectReference(project.getId(), project.getName(), project.getStatus(),
                        counts.getOrDefault(project.getId(), 0L).intValue()))
                .sorted(Comparator.comparing(ProjectReference::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<ApiKeyReference> keyReferences(List<ApiKey> keys, Map<UUID, Project> projectsById) {
        List<ApiKeyReference> result = new ArrayList<>();
        for (ApiKey key : keys) {
            Project project = projectsById.get(key.getProjectId());
            if (project != null) {
                result.add(new ApiKeyReference(key.getId(), key.getName(), key.getKeyPrefix(), key.getStatus().name(),
                        project.getId(), project.getName()));
            }
        }
        return result.stream().sorted(Comparator.comparing(ApiKeyReference::projectName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ApiKeyReference::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private String email(UUID userId) {
        return users.findById(userId).map(AppUser::getEmail).orElse("삭제된 사용자");
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public record OrganizationUserView(UUID id, String email, OrganizationRole organizationRole, String accountTier, boolean totpEnabled) { }
    public record TeamMemberReference(UUID userId, String email, TeamRole role) { }
    public record TeamMembershipReference(UUID teamId, String teamName, TeamRole role) { }
    public record ProjectReference(UUID id, String name, String status, int apiKeyCount) { }
    public record ApiKeyReference(UUID id, String name, String keyPrefix, String status, UUID projectId, String projectName) { }
    public record TeamDeletionPreview(UUID teamId, String teamName, List<TeamMemberReference> members,
                                      List<ProjectReference> projects, List<ApiKeyReference> apiKeys, String behavior) { }
    public record UserRemovalPreview(UUID userId, String email, OrganizationRole organizationRole,
                                     List<TeamMembershipReference> teamMemberships,
                                     List<ApiKeyReference> apiKeys, String behavior) { }
}
