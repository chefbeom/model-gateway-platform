package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.beta.BetaOperator;
import com.aiconnect.llmgateway.beta.BetaOperatorRepository;
import com.aiconnect.llmgateway.beta.BetaService;
import com.aiconnect.llmgateway.beta.BetaServiceRepository;
import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.ApiKeyStatus;
import com.aiconnect.llmgateway.domain.Organization;
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
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AccountInvitationService {
    static final UUID SYSTEM_ACTOR_ID = new UUID(0L, 0L);
    private static final int DEFAULT_EXPIRY_HOURS = 72;
    private static final int MAX_EXPIRY_HOURS = 720;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final AccountInvitationRepository invitations;
    private final AppUserRepository users;
    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository organizationMembers;
    private final TeamRepository teams;
    private final TeamMemberRepository teamMembers;
    private final ProjectRepository projects;
    private final ProjectMembershipRepository projectMemberships;
    private final BetaServiceRepository betaServices;
    private final BetaOperatorRepository betaOperators;
    private final IdentityService identity;
    private final AuditService audit;
    private final AuthProperties authProperties;
    private final EntityManager entityManager;
    @Autowired(required = false)
    private ApiKeyRepository apiKeys;
    @Autowired(required = false)
    private AuthRefreshTokenRepository refreshTokens;

    public AccountInvitationService(AccountInvitationRepository invitations,
                                    AppUserRepository users,
                                    OrganizationRepository organizations,
                                    OrganizationMemberRepository organizationMembers,
                                    TeamRepository teams,
                                    TeamMemberRepository teamMembers,
                                    ProjectRepository projects,
                                    ProjectMembershipRepository projectMemberships,
                                    BetaServiceRepository betaServices,
                                    BetaOperatorRepository betaOperators,
                                    IdentityService identity,
                                    AuditService audit,
                                    AuthProperties authProperties,
                                    EntityManager entityManager) {
        this.invitations = invitations;
        this.users = users;
        this.organizations = organizations;
        this.organizationMembers = organizationMembers;
        this.teams = teams;
        this.teamMembers = teamMembers;
        this.projects = projects;
        this.projectMemberships = projectMemberships;
        this.betaServices = betaServices;
        this.betaOperators = betaOperators;
        this.identity = identity;
        this.audit = audit;
        this.authProperties = authProperties;
        this.entityManager = entityManager;
    }

    @Transactional
    public IssuedAccountInvitation issue(AccountTier actorTier, UUID actorUserId, UUID scopedOrganizationId,
                                         CreateAccountInvitationRequest request) {
        if (request == null || request.targetTier() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TARGET_TIER_REQUIRED", "A target account tier is required.");
        }
        AccountTier targetTier = request.targetTier();
        if (targetTier == AccountTier.SYSTEM_ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                    "System administrator accounts cannot be created via invitations.");
        }
        if (actorTier == AccountTier.GENERAL_USER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                    "General users cannot issue account invitations.");
        }
        if (actorTier == AccountTier.PROJECT_DIRECTOR && targetTier != AccountTier.GENERAL_USER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                    "Project directors can only invite general users.");
        }
        if (actorTier == AccountTier.PROJECT_DIRECTOR && request.teamRole() != null
                && request.teamRole() != TeamRole.DEVELOPER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INVITATION_ROLE_FORBIDDEN",
                    "Project directors cannot grant elevated team roles through invitations.");
        }

        UUID organizationId = resolveOrganizationId(scopedOrganizationId, request);
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist."));

        if (actorTier == AccountTier.PROJECT_DIRECTOR) {
            OrganizationMember membership = actorUserId == null ? null : organizationMembers
                    .findByIdOrganizationIdAndIdUserId(organizationId, actorUserId)
                    .orElse(null);
            if (membership == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                        "Project directors can only issue invitations for their own organization.");
            }
        }

        if (request.teamId() != null) {
            Team team = teams.findById(request.teamId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "The team does not exist."));
            if (!organization.getId().equals(team.getOrganizationId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "The team belongs to another organization.");
            }
        }
        if (request.projectId() != null) {
            Project project = projects.findById(request.projectId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
            if (!organization.getId().equals(project.getOrganizationId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "The project belongs to another organization.");
            }
        }
        if ((targetTier == AccountTier.PROJECT_DIRECTOR || actorTier == AccountTier.PROJECT_DIRECTOR)
                && request.projectId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PROJECT_SCOPE_REQUIRED", "A project must be specified for this invitation.");
        }
        if (actorTier == AccountTier.PROJECT_DIRECTOR) {
            if (request.teamId() != null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INVITATION_ROLE_FORBIDDEN", "A project invitation cannot grant team-wide membership.");
            }
            boolean manager = projectMemberships.findById(new ProjectMembershipId(request.projectId(), actorUserId))
                    .map(ProjectMembership::getRole).filter(role -> role == ProjectMembership.Role.MANAGER).isPresent();
            if (!manager) throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED", "The director does not manage this project.");
        }
        if (request.betaServiceId() != null) {
            if (targetTier != AccountTier.PROJECT_DIRECTOR) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                        "Beta operator assignment via invitation is only permitted for project directors.");
            }
            BetaService betaService = betaServices.findById(request.betaServiceId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BETA_NOT_FOUND", "The beta service does not exist."));
            if (!organization.getId().equals(betaService.getOrganizationId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH", "The beta service belongs to another organization.");
            }
        }

        String normalizedEmail = normalizeOptionalEmail(request.expectedEmail());
        int expiryHours = request.expiresInHours() == null ? DEFAULT_EXPIRY_HOURS : request.expiresInHours();
        if (expiryHours < 1 || expiryHours > MAX_EXPIRY_HOURS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_INVITATION_EXPIRY_INVALID",
                    "Invitation expiry must be between 1 and " + MAX_EXPIRY_HOURS + " hours.");
        }

        TeamRole effectiveTeamRole = request.teamRole();
        if (effectiveTeamRole == null && (request.teamId() != null || request.projectId() != null)) {
            effectiveTeamRole = targetTier == AccountTier.PROJECT_DIRECTOR ? TeamRole.TEAM_ADMIN : TeamRole.DEVELOPER;
        }

        String rawCode = generateInvitationCode();
        String codePrefix = rawCode.substring(0, Math.min(12, rawCode.length()));
        Instant expiresAt = Instant.now().plusSeconds(expiryHours * 3600L);
        UUID effectiveIssuerId = actorUserId != null ? actorUserId : SYSTEM_ACTOR_ID;
        boolean effectiveCanIssueApiKeys = targetTier == AccountTier.PROJECT_DIRECTOR
                || (request.projectId() != null && Boolean.TRUE.equals(request.canIssueApiKeys()));

        AccountInvitation saved = invitations.save(new AccountInvitation(
                hashCode(rawCode),
                codePrefix,
                targetTier,
                organization.getId(),
                request.teamId(),
                effectiveTeamRole,
                request.projectId(),
                effectiveCanIssueApiKeys,
                request.betaServiceId(),
                normalizedEmail,
                effectiveIssuerId,
                actorTier,
                expiresAt
        ));

        audit.record(organization.getId(), actorUserId, "ACCOUNT_INVITATION_ISSUED", "ACCOUNT_INVITATION", saved.getId(),
                Map.of("targetTier", targetTier.name(), "codePrefix", codePrefix, "canIssueApiKeys", effectiveCanIssueApiKeys));

        return new IssuedAccountInvitation(
                saved.getId(),
                rawCode,
                "/#register?code=" + rawCode,
                saved.getTargetTier(),
                saved.getOrganizationId(),
                saved.getTeamId(),
                saved.getTeamRole(),
                saved.getProjectId(),
                saved.isCanIssueApiKeys(),
                saved.getBetaServiceId(),
                saved.getExpectedEmail(),
                saved.getExpiresAt()
        );
    }

    @Transactional(readOnly = true)
    public InspectInvitationResponse inspect(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return invalidInspect();
        }
        AccountInvitation inv = invitations.findByTokenHash(hashCode(rawCode.trim())).orElse(null);
        Instant now = Instant.now();
        if (inv == null || !inv.isPending(now)) {
            return invalidInspect();
        }
        Organization org = organizations.findById(inv.getOrganizationId()).orElse(null);
        if (org == null || !"ACTIVE".equalsIgnoreCase(org.getStatus())) {
            return invalidInspect();
        }
        String teamName = null;
        if (inv.getTeamId() != null) {
            Team team = teams.findById(inv.getTeamId()).orElse(null);
            if (team == null) return invalidInspect();
            teamName = team.getName();
        }
        String projectName = null;
        if (inv.getProjectId() != null) {
            Project project = projects.findById(inv.getProjectId()).orElse(null);
            if (project == null || !"ACTIVE".equalsIgnoreCase(project.getStatus())) return invalidInspect();
            projectName = project.getName();
        }
        return new InspectInvitationResponse(
                true,
                inv.getTargetTier(),
                inv.getOrganizationId(),
                org.getName(),
                inv.getTeamId(),
                teamName,
                inv.getProjectId(),
                projectName,
                inv.isCanIssueApiKeys(),
                inv.getExpectedEmail(),
                inv.getExpiresAt()
        );
    }

    @Transactional
    public IdentityService.Session accept(String rawCode, String email, String password) {
        if (rawCode == null || rawCode.isBlank()) {
            throw invalidInvitation();
        }
        String normalizedEmail = normalizeRequiredEmail(email);
        Instant now = Instant.now();

        AccountInvitation inv = invitations.findByTokenHashForUpdate(hashCode(rawCode.trim()))
                .orElseThrow(this::invalidInvitation);
        if (!inv.isPending(now)) {
            throw invalidInvitation();
        }
        if (inv.getExpectedEmail() != null && !inv.getExpectedEmail().equals(normalizedEmail)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_INVITATION_EMAIL_MISMATCH",
                    "This invitation is restricted to a different email address.");
        }
        if (users.existsByEmail(normalizedEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS",
                    "A user with that email already exists.");
        }
        Organization organization = organizations.findById(inv.getOrganizationId())
                .orElseThrow(this::invalidInvitation);
        if (!"ACTIVE".equalsIgnoreCase(organization.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_NOT_ACTIVE", "The organization is not active.");
        }
        Project targetProject = null;
        if (inv.getProjectId() != null) {
            targetProject = projects.findById(inv.getProjectId())
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PROJECT_NOT_ACTIVE",
                            "The project is no longer active."));
            if (!inv.getOrganizationId().equals(targetProject.getOrganizationId())) {
                throw invalidInvitation();
            }
            if (!"ACTIVE".equalsIgnoreCase(targetProject.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "PROJECT_NOT_ACTIVE",
                        "The project is suspended or inactive.");
            }
        }

        UUID invitedBy = null;
        if (!SYSTEM_ACTOR_ID.equals(inv.getIssuedByUserId())) {
            AppUser inviter = users.findById(inv.getIssuedByUserId()).orElse(null);
            if (inviter == null || !inviter.isEnabled()) {
                throw new ApiException(HttpStatus.CONFLICT, "INVITATION_ISSUER_REVOKED",
                        "The invitation issuer account is disabled or no longer available.");
            }
            boolean issuerIsPlatformOrOrgAdmin = inviter.isPlatformAdmin()
                    || (AccountTier.effectiveFor(inviter) != AccountTier.PROJECT_DIRECTOR
                    && organizationMembers.findByIdOrganizationIdAndIdUserId(inv.getOrganizationId(), inviter.getId())
                    .map(member -> member.getRole() == OrganizationRole.ORGANIZATION_ADMIN).orElse(false));
            if (inv.getProjectId() != null && !issuerIsPlatformOrOrgAdmin) {
                boolean stillManager = projectMemberships.existsByIdProjectIdAndIdUserIdAndRole(
                        inv.getProjectId(), inviter.getId(), ProjectMembership.Role.MANAGER);
                if (!stillManager) {
                    throw new ApiException(HttpStatus.CONFLICT, "INVITATION_ISSUER_REVOKED",
                            "The invitation issuer is no longer an active manager of this project.");
                }
            }
            invitedBy = inviter.getId();
        }

        AppUser created = identity.createUser(normalizedEmail, password, false, inv.getTargetTier(), invitedBy);
        if (inv.getProjectId() != null) { created.enforceProjectScope(); users.save(created); }
        OrganizationRole orgRole = OrganizationRole.DEVELOPER;
        identity.grantMembership(inv.getOrganizationId(), created.getId(), orgRole);

        TeamRole teamRole = inv.getTeamRole() != null
                ? inv.getTeamRole()
                : (inv.getTargetTier() == AccountTier.PROJECT_DIRECTOR ? TeamRole.TEAM_ADMIN : TeamRole.DEVELOPER);

        if (inv.getTeamId() != null && inv.getProjectId() == null) {
            Team team = teams.findById(inv.getTeamId()).orElseThrow(this::invalidInvitation);
            if (!inv.getOrganizationId().equals(team.getOrganizationId())) {
                throw invalidInvitation();
            }
            assignTeamMember(team.getId(), created.getId(), teamRole);
        }

        if (targetProject != null) {
            ProjectMembership.Role memberRole = inv.getTargetTier() == AccountTier.PROJECT_DIRECTOR
                    ? ProjectMembership.Role.MANAGER : ProjectMembership.Role.MEMBER;
            boolean canIssue = memberRole == ProjectMembership.Role.MANAGER || inv.isCanIssueApiKeys();
            projectMemberships.save(new ProjectMembership(targetProject.getId(), created.getId(), memberRole, canIssue));
        }

        if (inv.getBetaServiceId() != null && inv.getTargetTier() == AccountTier.PROJECT_DIRECTOR) {
            BetaService betaService = betaServices.findById(inv.getBetaServiceId()).orElseThrow(this::invalidInvitation);
            BetaOperator.Key opKey = new BetaOperator.Key(betaService.getId(), created.getId());
            if (!betaOperators.existsById(opKey)) {
                betaOperators.save(new BetaOperator(betaService.getId(), created.getId()));
            }
        }

        inv.consume(created.getId(), now);
        invitations.save(inv);

        audit.record(inv.getOrganizationId(), created.getId(), "ACCOUNT_INVITATION_ACCEPTED", "ACCOUNT_INVITATION", inv.getId(),
                Map.of("targetTier", inv.getTargetTier().name(), "email", created.getEmail()));

        return identity.issueInitialSession(created);
    }

    @Transactional
    public void revokeProjectMembershipCascade(Project project, AppUser targetUser, ProjectMembership membership,
                                               boolean revokeRefreshTokensAlways, UUID actorUserId, String auditAction) {
        if (!targetUser.isProjectScopeEnforced()) {
            targetUser.enforceProjectScope();
            users.save(targetUser);
        }
        projectMemberships.delete(membership);
        projectMemberships.flush();

        int revokedApiKeys = 0;
        if (apiKeys != null) {
            List<ApiKey> activeKeys = apiKeys.findByProjectIdAndIssuedByUserIdAndStatus(
                    project.getId(), targetUser.getId(), ApiKeyStatus.ACTIVE);
            if (!activeKeys.isEmpty()) {
                activeKeys.forEach(ApiKey::revoke);
                apiKeys.saveAll(activeKeys);
                revokedApiKeys = activeKeys.size();
            }
        }

        Instant now = Instant.now();
        int revokedInvitations = 0;
        List<AccountInvitation> pendingInvites = invitations
                .findByProjectIdAndIssuedByUserIdAndConsumedAtIsNullAndRevokedAtIsNull(project.getId(), targetUser.getId());
        for (AccountInvitation pending : pendingInvites) {
            AccountInvitation locked = invitations.findByIdForUpdate(pending.getId()).orElse(null);
            if (locked != null && locked.getConsumedAt() == null && locked.getRevokedAt() == null) {
                locked.revoke(now);
                invitations.save(locked);
                revokedInvitations++;
            }
        }

        boolean revokeTokens = revokeRefreshTokensAlways
                || (projectMemberships.countByIdUserId(targetUser.getId()) == 0
                && AccountTier.effectiveFor(targetUser) == AccountTier.GENERAL_USER);
        int revokedTokens = 0;
        if (revokeTokens && refreshTokens != null) {
            List<AuthRefreshToken> activeTokens = refreshTokens.findByUserIdAndRevokedAtIsNull(targetUser.getId());
            if (!activeTokens.isEmpty()) {
                activeTokens.forEach(AuthRefreshToken::revoke);
                refreshTokens.saveAll(activeTokens);
                revokedTokens = activeTokens.size();
            }
        }

        audit.record(project.getOrganizationId(), actorUserId, auditAction, "PROJECT", project.getId(),
                Map.of("userId", targetUser.getId().toString(),
                        "revokedApiKeyCount", revokedApiKeys,
                        "revokedInvitationCount", revokedInvitations,
                        "revokedRefreshTokenCount", revokedTokens));
    }

    @Transactional(readOnly = true)
    public List<AccountInvitationView> listPlatform() {
        return invitations.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccountInvitationView> listOrganization(UUID organizationId) {
        if (!organizations.existsById(organizationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist.");
        }
        return invitations.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AccountInvitationView> listIssuedByDirector(UUID organizationId, UUID directorId) {
        if (directorId == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN", "An identified project director is required.");
        }
        return invitations.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .filter(inv -> directorId.equals(inv.getIssuedByUserId()))
                .map(this::toView)
                .toList();
    }

    @Transactional
    public void revokePlatform(UUID invitationId, UUID actorUserId) {
        AccountInvitation inv = invitations.findByIdForUpdate(invitationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_INVITATION_NOT_FOUND", "The invitation does not exist."));
        revokeInternal(inv, actorUserId);
    }

    @Transactional
    public void revokeOrganization(UUID organizationId, UUID invitationId, AccountTier actorTier, UUID actorUserId) {
        if (!organizations.existsById(organizationId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist.");
        }
        AccountInvitation inv = invitations.findByIdForUpdate(invitationId)
                .filter(item -> item.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_INVITATION_NOT_FOUND", "The invitation does not exist."));
        if (actorTier == AccountTier.GENERAL_USER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN", "General users cannot revoke invitations.");
        }
        if (actorTier == AccountTier.PROJECT_DIRECTOR && inv.getTargetTier() != AccountTier.GENERAL_USER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                    "Project directors can only revoke general user invitations.");
        }
        if (actorTier == AccountTier.PROJECT_DIRECTOR &&
                (actorUserId == null || !actorUserId.equals(inv.getIssuedByUserId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_TIER_FORBIDDEN",
                    "Project directors may only revoke invitations they issued.");
        }
        revokeInternal(inv, actorUserId);
    }

    private void revokeInternal(AccountInvitation inv, UUID actorUserId) {
        if (inv.getConsumedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_INVITATION_USED", "The invitation has already been used.");
        }
        if (inv.getRevokedAt() == null) {
            inv.revoke(Instant.now());
            invitations.save(inv);
            audit.record(inv.getOrganizationId(), actorUserId, "ACCOUNT_INVITATION_REVOKED", "ACCOUNT_INVITATION", inv.getId(),
                    Map.of("targetTier", inv.getTargetTier().name()));
        }
    }

    private void assignTeamMember(UUID teamId, UUID userId, TeamRole role) {
        if (teamMembers.findByIdTeamIdAndIdUserId(teamId, userId).isEmpty()) {
            teamMembers.save(new TeamMember(teamId, userId, role));
        }
    }

    private String projectAccessTeamName(String projectName) {
        String base = (projectName == null || projectName.isBlank()) ? "Project" : projectName.trim();
        String suffix = " Access";
        int maxBase = 120 - suffix.length();
        return (base.length() > maxBase ? base.substring(0, maxBase) : base) + suffix;
    }

    private UUID resolveOrganizationId(UUID scopedOrganizationId, CreateAccountInvitationRequest request) {
        if (scopedOrganizationId != null) {
            if (request.organizationId() != null && !scopedOrganizationId.equals(request.organizationId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_MISMATCH",
                        "Organization ID in request body does not match the path organization.");
            }
            return scopedOrganizationId;
        }
        if (request.organizationId() != null) {
            return request.organizationId();
        }
        if (request.teamId() != null) {
            return teams.findById(request.teamId())
                    .map(Team::getOrganizationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "The team does not exist."));
        }
        if (request.projectId() != null) {
            return projects.findById(request.projectId())
                    .map(Project::getOrganizationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
        }
        if (request.betaServiceId() != null) {
            return betaServices.findById(request.betaServiceId())
                    .map(BetaService::getOrganizationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BETA_NOT_FOUND", "The beta service does not exist."));
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_REQUIRED", "An organizationId is required.");
    }

    private AccountInvitationView toView(AccountInvitation inv) {
        String orgName = organizations.findById(inv.getOrganizationId()).map(Organization::getName).orElse("(deleted organization)");
        String teamName = inv.getTeamId() == null ? null : teams.findById(inv.getTeamId()).map(Team::getName).orElse(null);
        String projectName = inv.getProjectId() == null ? null : projects.findById(inv.getProjectId()).map(Project::getName).orElse(null);
        String issuerEmail = SYSTEM_ACTOR_ID.equals(inv.getIssuedByUserId())
                ? "SYSTEM / BREAK-GLASS"
                : users.findById(inv.getIssuedByUserId()).map(AppUser::getEmail).orElse("삭제된 사용자");
        return new AccountInvitationView(
                inv.getId(),
                inv.getCodePrefix(),
                inv.getTargetTier(),
                inv.getOrganizationId(),
                orgName,
                inv.getTeamId(),
                teamName,
                inv.getTeamRole(),
                inv.getProjectId(),
                projectName,
                inv.isCanIssueApiKeys(),
                inv.getBetaServiceId(),
                inv.getExpectedEmail(),
                inv.getIssuedByUserId(),
                issuerEmail,
                inv.getIssuedByTier().name(),
                inv.status(Instant.now()),
                inv.getExpiresAt(),
                inv.getConsumedAt(),
                inv.getRevokedAt(),
                inv.getCreatedAt()
        );
    }

    private String normalizeOptionalEmail(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return normalizeRequiredEmail(raw);
    }

    private String normalizeRequiredEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_REQUIRED", "A valid email address is required.");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 320 || !SIMPLE_EMAIL.matcher(normalized).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_INVALID", "The email address format is invalid.");
        }
        return normalized;
    }

    private String generateInvitationCode() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "aci_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashCode(String rawCode) {
        try {
            if (rawCode == null || rawCode.length() > 128) {
                throw new IllegalArgumentException("Invalid invitation code length");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authProperties.refreshPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("account-invite:" + rawCode).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw invalidInvitation();
        }
    }

    private InspectInvitationResponse invalidInspect() {
        return new InspectInvitationResponse(false, null, null, null, null, null, null, null, false, null, null);
    }

    private ApiException invalidInvitation() {
        return new ApiException(HttpStatus.BAD_REQUEST, "ACCOUNT_INVITATION_INVALID",
                "The invitation code is invalid, revoked, or expired.");
    }

    public record CreateAccountInvitationRequest(
            AccountTier targetTier,
            UUID organizationId,
            UUID teamId,
            TeamRole teamRole,
            UUID projectId,
            UUID betaServiceId,
            String expectedEmail,
            Integer expiresInHours,
            Boolean canIssueApiKeys
    ) {
        public CreateAccountInvitationRequest(AccountTier targetTier, UUID organizationId, UUID teamId, TeamRole teamRole,
                                              UUID projectId, UUID betaServiceId, String expectedEmail, Integer expiresInHours) {
            this(targetTier, organizationId, teamId, teamRole, projectId, betaServiceId, expectedEmail, expiresInHours, null);
        }
    }

    public record IssuedAccountInvitation(
            UUID id,
            String code,
            String registrationPath,
            AccountTier targetTier,
            UUID organizationId,
            UUID teamId,
            TeamRole teamRole,
            UUID projectId,
            boolean canIssueApiKeys,
            UUID betaServiceId,
            String expectedEmail,
            Instant expiresAt
    ) { }

    public record InspectInvitationResponse(
            boolean valid,
            AccountTier targetTier,
            UUID organizationId,
            String organizationName,
            UUID teamId,
            String teamName,
            UUID projectId,
            String projectName,
            boolean canIssueApiKeys,
            String expectedEmail,
            Instant expiresAt
    ) { }

    public record AccountInvitationView(
            UUID id,
            String codePrefix,
            AccountTier targetTier,
            UUID organizationId,
            String organizationName,
            UUID teamId,
            String teamName,
            TeamRole teamRole,
            UUID projectId,
            String projectName,
            boolean canIssueApiKeys,
            UUID betaServiceId,
            String expectedEmail,
            UUID issuedByUserId,
            String issuedByEmail,
            String issuedByTier,
            String status,
            Instant expiresAt,
            Instant consumedAt,
            Instant revokedAt,
            Instant createdAt
    ) { }
}
