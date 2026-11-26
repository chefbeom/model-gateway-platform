package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.admin.AdminDtos;
import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.admin.EndpointAdministrationService;
import com.aiconnect.llmgateway.admin.PlatformAdministrationService;
import com.aiconnect.llmgateway.beta.BetaAdministration;
import com.aiconnect.llmgateway.beta.BetaOperator;
import com.aiconnect.llmgateway.beta.BetaOperatorRepository;
import com.aiconnect.llmgateway.domain.ExternalAccessStatus;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.ExternalProviderType;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.Incident;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.ProjectExternalAccess;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.identity.AccessTokenService;
import com.aiconnect.llmgateway.identity.AccountInvitationRepository;
import com.aiconnect.llmgateway.identity.AccountInvitationService;
import com.aiconnect.llmgateway.identity.AccountTier;
import com.aiconnect.llmgateway.identity.AppUser;
import com.aiconnect.llmgateway.identity.AppUserRepository;
import com.aiconnect.llmgateway.identity.AuditLogRepository;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.AuthProperties;
import com.aiconnect.llmgateway.identity.AuthRefreshToken;
import com.aiconnect.llmgateway.identity.AuthRefreshTokenRepository;
import com.aiconnect.llmgateway.identity.IdentityService;
import com.aiconnect.llmgateway.identity.OrganizationAccessService;
import com.aiconnect.llmgateway.identity.OrganizationRole;
import com.aiconnect.llmgateway.identity.ProjectMembership;
import com.aiconnect.llmgateway.identity.ProjectMembershipId;
import com.aiconnect.llmgateway.identity.ProjectMembershipRepository;
import com.aiconnect.llmgateway.identity.TotpService;
import com.aiconnect.llmgateway.identity.UserAdminController;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import com.aiconnect.llmgateway.alert.UsageAlertDeliveryRepository;
import com.aiconnect.llmgateway.notification.NotificationChannelRepository;
import com.aiconnect.llmgateway.notification.NotificationChannelService;
import com.aiconnect.llmgateway.notification.NotificationChannelType;
import com.aiconnect.llmgateway.notification.NotificationDeliveryRepository;
import com.aiconnect.llmgateway.notification.NotificationService;
import com.aiconnect.llmgateway.quota.ProjectQuota;
import com.aiconnect.llmgateway.quota.ProjectQuotaRepository;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.IncidentRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectExternalAccessRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.retention.ContentRetentionMode;
import com.aiconnect.llmgateway.retention.RequestContentService;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:security_hardening_suite;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class SecurityHardeningIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired IdentityService identity;
    @Autowired TotpService totpService;
    @Autowired AccessTokenService accessTokens;
    @Autowired AuthProperties authProperties;
    @Autowired AppUserRepository users;
    @Autowired com.aiconnect.llmgateway.identity.OrganizationMemberRepository organizationMembers;
    @Autowired AuthRefreshTokenRepository refreshTokens;
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMembershipRepository projectMemberships;
    @Autowired LlmServiceRepository services;
    @Autowired BetaAdministration betaAdministration;
    @Autowired BetaOperatorRepository betaOperators;
    @Autowired OrganizationAccessService organizationAccess;
    @Autowired AccountInvitationService accountInvitations;
    @Autowired AccountInvitationRepository accountInvitationRepo;
    @Autowired NotificationChannelService notificationChannels;
    @Autowired ControlPlaneService controlPlane;
    @Autowired AuditLogRepository auditLogs;
    @Autowired PlatformAdministrationService platformAdminService;
    @Autowired UserAdminController userAdminController;
    @Autowired TeamRepository teams;
    @Autowired ProjectQuotaRepository projectQuotas;
    @Autowired EndpointAdministrationService endpointAdmin;
    @Autowired RuntimeEndpointRepository runtimeEndpoints;
    @Autowired NotificationChannelRepository notificationChannelRepo;
    @Autowired NotificationDeliveryRepository notificationDeliveries;
    @Autowired UsageAlertDeliveryRepository usageAlertDeliveries;
    @Autowired IncidentRepository incidents;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired RequestContentService requestContentService;
    @Autowired LlmRequestRepository llmRequests;
    @Autowired ExternalProviderRepository externalProviders;
    @Autowired ProjectExternalAccessRepository externalAccessRepo;
    @Autowired SecretCipher secretCipher;

    @Test
    void loginLockoutPreMfaExpiryAndEnrollmentRevokeOtherPreMfaSessions() throws Exception {
        AppUser victim = identity.createUser("hardening-victim@example.com", "StrongPassword123!", false, AccountTier.PROJECT_DIRECTOR, null);

        // 1. Pre-MFA session issued at Step 1 must have short TTL (<= preMfaRefreshTokenSeconds)
        IdentityService.Session attackerPreMfa = identity.login("hardening-victim@example.com", "StrongPassword123!");
        AccessTokenService.ParsedSession parsedAttacker = accessTokens.parseSession(attackerPreMfa.accessToken());
        AuthRefreshToken attackerRow = refreshTokens.findById(parsedAttacker.sessionId()).orElseThrow();
        assertThat(attackerRow.getExpiresAt())
                .isBeforeOrEqualTo(Instant.now().plusSeconds(authProperties.preMfaRefreshTokenSeconds() + 5));

        // Attacker refresh must stay capped to the original pre-MFA deadline
        IdentityService.Session attackerRefreshed = identity.refresh(attackerPreMfa.refreshToken());
        AuthRefreshToken attackerRefreshedRow = refreshTokens.findById(accessTokens.parseSession(attackerRefreshed.accessToken()).sessionId()).orElseThrow();
        assertThat(attackerRefreshedRow.getExpiresAt()).isBeforeOrEqualTo(attackerRow.getExpiresAt().plusSeconds(1));

        // 2. Victim logs in and completes QR TOTP enrollment; all competing pre-MFA sessions must be revoked
        String loginRes = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"hardening-victim@example.com\",\"password\":\"StrongPassword123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("ENROLLMENT_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        String victimPreMfa = mapper.readTree(loginRes).path("accessToken").asText();

        String setupJson = mvc.perform(post("/api/auth/totp/setup").header("Authorization", "Bearer " + victimPreMfa))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = mapper.readTree(setupJson).path("secret").asText();
        String code = totpService.generateCode(secret, Instant.now());

        mvc.perform(post("/api/auth/totp/enroll")
                        .header("Authorization", "Bearer " + victimPreMfa)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.mfaState").value("VERIFIED"));

        // Attacker's previously issued pre-MFA access token and refresh cookie are now dead
        mvc.perform(get("/api/portal/session").header("Authorization", "Bearer " + attackerRefreshed.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("aiconnect_refresh", attackerRefreshed.refreshToken())))
                .andExpect(status().isUnauthorized());

        // 3. 5 bad password guesses lock account even against subsequent valid password
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"hardening-victim@example.com\",\"password\":\"WrongPassword" + i + "!!\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"hardening-victim@example.com\",\"password\":\"WrongPasswordFinal!\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        // Even the real password must be blocked while locked
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"hardening-victim@example.com\",\"password\":\"StrongPassword123!\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        // 4. TOTP lockout must also block /api/auth/totp/setup
        AppUser totpLockTarget = identity.createUser("totp-locked@example.com", "StrongPassword123!", false);
        IdentityService.Session setupSession = identity.login("totp-locked@example.com", "StrongPassword123!");
        mvc.perform(post("/api/auth/totp/setup").header("Authorization", "Bearer " + setupSession.accessToken()))
                .andExpect(status().isOk());
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/auth/totp/enroll")
                            .header("Authorization", "Bearer " + setupSession.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"000000\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/totp/enroll")
                        .header("Authorization", "Bearer " + setupSession.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOTP_LOCKED"));
        mvc.perform(post("/api/auth/totp/setup").header("Authorization", "Bearer " + setupSession.accessToken()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOTP_LOCKED"));
        assertThat(totpLockTarget.getEmail()).isEqualTo("totp-locked@example.com");
    }

    @Test
    void jwtRejectsTamperedHeaderAlgorithmAndFutureIssuedAt() throws Exception {
        AppUser user = identity.createUser("jwt-check@example.com", "StrongPassword123!", false);
        String headerJson = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long now = Instant.now().getEpochSecond();
        String payloadJson = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + user.getId() + "\",\"email\":\"jwt-check@example.com\",\"platform_admin\":true,\"iat\":" + now + ",\"exp\":" + (now + 300) + "}").getBytes(StandardCharsets.UTF_8));
        String forged = headerJson + "." + payloadJson + "." + hmac(headerJson + "." + payloadJson, authProperties.signingKey());

        assertThatThrownBy(() -> accessTokens.parse(forged))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_ACCESS_TOKEN");
    }

    @Test
    void offboardingRemovesProjectMembershipsInvitationsBetaOperatorAndSessions() {
        Organization org = organizations.save(new Organization("Offboard Sec Org"));
        Project project = projects.save(new Project(org.getId(), "Offboard Project"));
        LlmService service = services.save(new LlmService(org.getId(), "offboard-svc", "Offboard Svc",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        var beta = betaAdministration.create(org.getId(), service.getId());

        AppUser member = identity.createUser("ex-director@example.com", "StrongPassword123!", false, AccountTier.PROJECT_DIRECTOR, null);
        identity.grantMembership(org.getId(), member.getId(), OrganizationRole.DEVELOPER);
        projectMemberships.save(new ProjectMembership(project.getId(), member.getId(), ProjectMembership.Role.MANAGER, true));
        betaAdministration.assign(beta.getId(), member.getId());

        var issuedInvite = accountInvitations.issue(AccountTier.PROJECT_DIRECTOR, member.getId(), org.getId(),
                new AccountInvitationService.CreateAccountInvitationRequest(AccountTier.GENERAL_USER, org.getId(), null, null, project.getId(), null, "dev@example.com", 24, true));
        IdentityService.Session session = identity.login("ex-director@example.com", "StrongPassword123!");

        organizationAccess.removeOrganizationUser(org.getId(), member.getId());

        assertThat(projectMemberships.findById(new ProjectMembershipId(project.getId(), member.getId()))).isEmpty();
        assertThat(betaOperators.existsById(new BetaOperator.Key(beta.getId(), member.getId()))).isFalse();
        assertThat(accountInvitationRepo.findById(issuedInvite.id()).orElseThrow().getRevokedAt()).isNotNull();
        assertThat(refreshTokens.findByUserIdAndRevokedAtIsNull(member.getId())).isEmpty();
        assertThat(session.accessToken()).isNotBlank();
    }

    @Test
    void notificationChannelsAndRuntimeEndpointsBlockMetadataSsrfAndPathInjection() {
        Organization org = organizations.save(new Organization("SSRF Sec Org"));
        InferenceNode node = nodes.save(new InferenceNode(org.getId(), "ssrf-node", null, "DIRECT", null));

        for (String dangerousUrl : List.of(
                "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
                "http://127.0.0.1:8080/api/admin/platform/users",
                "http://10.0.0.12/internal-hook",
                "http://localhost:6379/"
        )) {
            assertThatThrownBy(() -> notificationChannels.create(org.getId(), NotificationChannelType.DISCORD_WEBHOOK, dangerousUrl, null))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("EXTERNAL_PROVIDER_URL_FORBIDDEN");
        }

        for (String badToken : List.of("123:abc/../../getMe", "123:abc?param=1", "123:abc#frag", "bad token")) {
            assertThatThrownBy(() -> notificationChannels.create(org.getId(), NotificationChannelType.TELEGRAM_BOT, "-100123", badToken))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("INVALID_NOTIFICATION_SECRET");
        }

        // Runtime endpoint allows LAN/Tailnet while rejecting cloud metadata and link-local
        assertThat(controlPlane.create(new AdminDtos.CreateEndpoint(node.getId(), "LAN", RuntimeType.LM_STUDIO,
                "http://100.64.10.5:1234", null, null, null, null)).getBaseUrl()).isEqualTo("http://100.64.10.5:1234");
        for (String metadataUrl : List.of(
                "http://169.254.169.254/latest/meta-data",
                "http://[fd00:ec2::254]/latest/meta-data",
                "http://metadata.google.internal/computeMetadata/v1",
                "http://0.0.0.0:1234"
        )) {
            assertThatThrownBy(() -> controlPlane.create(new AdminDtos.CreateEndpoint(node.getId(), "Bad", RuntimeType.LM_STUDIO,
                    metadataUrl, null, null, null, null)))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("RUNTIME_ENDPOINT_URL_FORBIDDEN");
        }
        assertThat(auditLogs).isNotNull();
    }

    @Test
    void verifyTotpRevokesStrayPreMfaSessionsDirectorResetScopedToProjectAndDeleteUserAudited() {
        Organization org = organizations.save(new Organization("MFA Audit Org"));
        Project projectA = projects.save(new Project(org.getId(), "Project A"));
        Project projectB = projects.save(new Project(org.getId(), "Project B"));

        AppUser director = identity.createUser("director-scoped@example.com", "StrongPassword123!", false, AccountTier.PROJECT_DIRECTOR, null);
        AppUser devB = identity.createUser("dev-b@example.com", "StrongPassword123!", false, AccountTier.GENERAL_USER, null);
        identity.grantMembership(org.getId(), director.getId(), OrganizationRole.DEVELOPER);
        identity.grantMembership(org.getId(), devB.getId(), OrganizationRole.DEVELOPER);
        projectMemberships.save(new ProjectMembership(projectA.getId(), director.getId(), ProjectMembership.Role.MANAGER, true));
        projectMemberships.save(new ProjectMembership(projectB.getId(), devB.getId(), ProjectMembership.Role.MEMBER, false));

        // Director of Project A must NOT reset TOTP for General User in Project B
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(
                                director.getId(), director.getEmail(), false, AccountTier.PROJECT_DIRECTOR, true, true),
                        null, List.of()));
        try {
            assertThatThrownBy(() -> userAdminController.resetOrganizationUserTotp(
                    new MockHttpServletRequest(), org.getId(), devB.getId()))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("PROJECT_ACCESS_DENIED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        // Enroll TOTP for devB, then verify that completing Step 2 revokes abandoned pre-MFA sessions
        // while keeping another already-verified session active
        IdentityService.Session enrollSession = identity.login(devB.getEmail(), "StrongPassword123!");
        var setup = totpService.beginSetup(devB.getId());
        var enrolled = identity.enrollTotp(devB.getId(), accessTokens.parseSession(enrollSession.accessToken()).sessionId(),
                totpService.generateCode(setup.secret(), Instant.now()));
        UUID verifiedSessionId = accessTokens.parseSession(enrolled.session().accessToken()).sessionId();

        IdentityService.Session strayPreMfa = identity.login(devB.getEmail(), "StrongPassword123!");
        IdentityService.Session legitimateStep1 = identity.login(devB.getEmail(), "StrongPassword123!");
        UUID strayId = accessTokens.parseSession(strayPreMfa.accessToken()).sessionId();
        UUID legId = accessTokens.parseSession(legitimateStep1.accessToken()).sessionId();

        String verifyCode = totpService.generateCode(setup.secret(), Instant.now().plusSeconds(30));
        identity.verifyTotp(devB.getId(), legId, verifyCode);

        assertThat(refreshTokens.findById(verifiedSessionId).orElseThrow().isUsable(Instant.now())).isTrue();
        assertThat(refreshTokens.findById(strayId).orElseThrow().isUsable(Instant.now())).isFalse();

        // Deleting user must emit PLATFORM_USER_DELETED audit log
        platformAdminService.deleteUser(devB.getId(), devB.getEmail());
        assertThat(auditLogs.findAll()).anyMatch(entry ->
                "PLATFORM_USER_DELETED".equals(entry.getAction()) && devB.getId().toString().equals(entry.getResourceId()));
    }

    @Test
    void hardensDirectorEscalationsInviteOnlyOrgSuspendBetaAuthAndAuthLogoutOrigin() throws Exception {
        Organization org = organizations.save(new Organization("Hardening RBAC Org"));
        Team teamA = teams.save(new Team(org.getId(), "Team A"));
        Project project = projects.save(new Project(org.getId(), teamA.getId(), "Hardening Proj"));
        LlmService svc = services.save(new LlmService(org.getId(), "hardening-svc", "Hardening Svc",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        projectQuotas.save(new ProjectQuota(project.getId(), 20, 5000L));
        ExternalProvider ext = externalProviders.save(new ExternalProvider(
                org.getId(), ExternalProviderType.OPENAI, "OpenAI Sec", "https://api.openai.com/v1", secretCipher.encrypt("sk-test-1234567890")));
        ProjectExternalAccess extApproval = new ProjectExternalAccess(project.getId(), ext.getId(), null, "initial");
        extApproval.decide(ExternalAccessStatus.APPROVED, true, true, null, null, null, null);
        externalAccessRepo.save(extApproval);

        // Director & General User setup
        AppUser director = identity.createUser("sec-director@example.com", "StrongPassword123!", false, AccountTier.PROJECT_DIRECTOR, null);
        identity.grantMembership(org.getId(), director.getId(), OrganizationRole.DEVELOPER);
        projectMemberships.save(new ProjectMembership(project.getId(), director.getId(), ProjectMembership.Role.MANAGER, true));
        String directorJwt = loginVerifiedJwt(director);

        AppUser orgAdmin = identity.createUser("sec-orgadmin@example.com", "StrongPassword123!", false, AccountTier.GENERAL_USER, null);
        identity.grantMembership(org.getId(), orgAdmin.getId(), OrganizationRole.ORGANIZATION_ADMIN);
        AppUser member = identity.createUser("sec-member@example.com", "StrongPassword123!", false, AccountTier.GENERAL_USER, null);
        identity.grantMembership(org.getId(), member.getId(), OrganizationRole.DEVELOPER);
        projectMemberships.save(new ProjectMembership(project.getId(), member.getId(), ProjectMembership.Role.MEMBER, false));
        String memberJwt = loginVerifiedJwt(member);
        String orgAdminJwt = loginVerifiedJwt(orgAdmin);

        // 1. Director cannot detach teamId, delete project, self-grant service access, un-suspend project, change content policy, or raise org quota
        mvc.perform(patch("/api/admin/projects/{id}", project.getId())
                        .header("Authorization", "Bearer " + directorJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hardening Proj\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_TEAM_CHANGE_DENIED"));

        mvc.perform(delete("/api/admin/projects/{id}", project.getId())
                        .header("Authorization", "Bearer " + directorJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_ADMIN_REQUIRED"));

        mvc.perform(post("/api/admin/projects/{id}/service-access", project.getId())
                        .header("Authorization", "Bearer " + directorJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"" + svc.getId() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_ADMIN_REQUIRED"));

        mvc.perform(patch("/api/admin/projects/{id}/status", project.getId())
                        .header("Authorization", "Bearer " + directorJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"revokeActiveApiKeys\":false}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/admin/projects/{id}/status", project.getId())
                        .header("Authorization", "Bearer " + directorJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"revokeActiveApiKeys\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_ADMIN_REQUIRED"));
        mvc.perform(patch("/api/admin/projects/{id}/status", project.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"revokeActiveApiKeys\":false}"))
                .andExpect(status().isOk());

        mvc.perform(put("/api/admin/projects/{id}/content-policy", project.getId())
                        .header("Authorization", "Bearer " + directorJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FULL_ENCRYPTED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_ADMIN_REQUIRED"));

        mvc.perform(put("/api/admin/projects/{id}/quota", project.getId())
                        .header("Authorization", "Bearer " + directorJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestsPerMinute\":21,\"monthlyTokenLimit\":5000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUOTA_CEILING_EXCEEDED"));

        // 2. Invite-only user creation disabled; cross-tenant member grant restricted to platform admin
        mvc.perform(post("/api/admin/organizations/{orgId}/users", org.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"direct@example.com\",\"password\":\"StrongPassword123!\",\"organizationRole\":\"DEVELOPER\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("DIRECT_USER_CREATION_DISABLED"));
        mvc.perform(put("/api/admin/organizations/{orgId}/members/{uid}", org.getId(), member.getId())
                        .header("Authorization", "Bearer " + orgAdminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isForbidden());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(orgAdmin.getId(), orgAdmin.getEmail(), false, AccountTier.GENERAL_USER, true, true),
                        null, List.of()));
        try {
            assertThatThrownBy(() -> userAdminController.grantMembership(
                    new MockHttpServletRequest(), org.getId(), member.getId(),
                    new UserAdminController.SetMembership(OrganizationRole.DEVELOPER)))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("PLATFORM_ADMIN_REQUIRED");
        } finally {
            SecurityContextHolder.clearContext();
        }

        // 3. Unauthenticated beta services -> 401; GENERAL_USER admin keys/members -> 403; portal external-access overwrite -> 403/409
        mvc.perform(get("/api/beta/operator/services"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/admin/projects/{id}/api-keys", project.getId())
                        .header("Authorization", "Bearer " + memberJwt))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/projects/{id}/members", project.getId())
                        .header("Authorization", "Bearer " + memberJwt))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/portal/projects/{id}/external-access", project.getId())
                        .header("Authorization", "Bearer " + memberJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"" + ext.getId() + "\",\"reason\":\"reset\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/portal/projects/{id}/external-access", project.getId())
                        .header("Authorization", "Bearer " + directorJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":\"" + ext.getId() + "\",\"reason\":\"reset\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXTERNAL_ACCESS_ALREADY_ACTIVE"));

        // 4. Cross-origin Origin on /api/auth/refresh -> 403 ORIGIN_MISMATCH; Bearer-only logout revokes session
        mvc.perform(post("/api/auth/refresh")
                        .header("Origin", "https://evil.example")
                        .header("Host", "console.example.com"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_MISMATCH"));
        UUID memberSessionId = accessTokens.parseSession(memberJwt).sessionId();
        mvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + memberJwt))
                .andExpect(status().isNoContent());
        assertThat(refreshTokens.findById(memberSessionId).orElseThrow().isUsable(Instant.now())).isFalse();

        // 5. Suspended organization blocks ApiKeyService.authenticate
        var issuedKey = apiKeyService.issue(project.getId(), "org-suspend-test", null, director.getId(), null);
        platformAdminService.suspendOrganization(org.getId());
        assertThatThrownBy(() -> apiKeyService.authenticate("Bearer " + issuedKey.secret()))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("ORGANIZATION_SUSPENDED");
        platformAdminService.restoreOrganization(org.getId());

        // 6. deleteUser revokes active API keys issued by deleted user & protects last enabled platform admin
        assertThat(apiKeys.findById(issuedKey.id()).orElseThrow().getStatus().name()).isEqualTo("ACTIVE");
        platformAdminService.deleteUser(director.getId(), director.getEmail());
        assertThat(apiKeys.findById(issuedKey.id()).orElseThrow().getStatus().name()).isEqualTo("REVOKED");

        if (users.findAll().stream().filter(u -> u.isPlatformAdmin() && u.isEnabled()).count() == 0) {
            AppUser onlyAdmin = identity.createUser("only-platform-admin@example.com", "StrongPassword123!", true, AccountTier.SYSTEM_ADMIN, null);
            assertThatThrownBy(() -> platformAdminService.deleteUser(onlyAdmin.getId(), onlyAdmin.getEmail()))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("LAST_PLATFORM_ADMIN_FORBIDDEN");
        }
    }

    @Test
    void protectsSecretsInNotificationErrorsEndpointUrlsAndContentRetentionAudit() {
        Organization org = organizations.save(new Organization("Secret Leakage Audit Org"));
        Project project = projects.save(new Project(org.getId(), "Leak Audit Project"));
        InferenceNode node = nodes.save(new InferenceNode(org.getId(), "leak-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = runtimeEndpoints.save(new RuntimeEndpoint(
                node.getId(), "leak-ep", RuntimeType.LM_STUDIO, "http://100.64.10.9:1234", secretCipher.encrypt("secret-rt-token")));

        // 1. Runtime baseUrl change without replacing/clearing stored apiToken must be rejected
        assertThatThrownBy(() -> endpointAdmin.update(endpoint.getId(),
                new EndpointAdministrationService.UpdateCommand(null, "http://100.64.10.99:1234", null, null, false, null, false, null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("RUNTIME_URL_REQUIRES_NEW_TOKEN");

        // 2. NotificationService must never store decrypted Telegram token / URL in error_message
        notificationChannels.create(org.getId(), NotificationChannelType.TELEGRAM_BOT, "-100999888", "999999:SUPER_SECRET_BOT_TOKEN");
        Incident incident = incidents.save(new Incident(endpoint.getId(), "timeout probe"));
        RestClient failingClient = RestClient.builder()
                .requestFactory((uri, method) -> {
                    throw new ResourceAccessException("I/O error on POST request for \"" + uri + "\": timed out",
                            new java.net.SocketTimeoutException("Read timed out"));
                })
                .build();
        NotificationService customNotifier = new NotificationService(
                notificationChannelRepo, notificationDeliveries, usageAlertDeliveries, nodes, secretCipher, failingClient);
        customNotifier.incidentOpened(incident, endpoint, "timeout probe");
        var deliveries = notificationDeliveries.findByIncidentIdInOrderByCreatedAtAsc(List.of(incident.getId()));
        assertThat(deliveries).singleElement().satisfies(d -> {
            assertThat(d.getErrorMessage()).isEqualTo("NETWORK_TIMEOUT");
            assertThat(d.getErrorMessage()).doesNotContain("SUPER_SECRET_BOT_TOKEN");
        });

        // 3. Content retention skips blocked DLP requests & logs REQUEST_CONTENT_VIEWED with orgId under writable tx
        LlmService svc = services.save(new LlmService(org.getId(), "ret-svc", "Ret Svc",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        requestContentService.setPolicy(project.getId(), ContentRetentionMode.FULL_ENCRYPTED, 7);
        LlmRequest blockedReq = new LlmRequest("req_blocked_secret", project.getId(), null, null, svc, false);
        blockedReq.recordDataProtection("ENFORCE", "STRICT", "BLOCK", "[\"SECRET\"]", false);
        blockedReq.fail("DATA_POLICY_BLOCKED", 403, 5L, 0);
        llmRequests.saveAndFlush(blockedReq);
        requestContentService.capture("req_blocked_secret", "{\"secret\":\"sk-proj-secret\"}", null);
        assertThatThrownBy(() -> requestContentService.read(project.getId(), "req_blocked_secret"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("REQUEST_CONTENT_NOT_RETAINED");

        LlmRequest allowedReq = new LlmRequest("req_allowed_secret", project.getId(), null, null, svc, false);
        allowedReq.succeed(null, 5, 5, 10L, 200, 0);
        llmRequests.saveAndFlush(allowedReq);
        requestContentService.capture("req_allowed_secret", "{\"prompt\":\"hello\"}", "{\"reply\":\"world\"}");
        var stored = requestContentService.read(project.getId(), "req_allowed_secret");
        assertThat(stored.request()).contains("hello");
        assertThat(auditLogs.findAll()).anyMatch(entry ->
                "REQUEST_CONTENT_VIEWED".equals(entry.getAction()) && org.getId().equals(entry.getOrganizationId()));
    }

    private String loginVerifiedJwt(AppUser user) throws Exception {
        IdentityService.Session pre = identity.login(user.getEmail(), "StrongPassword123!");
        UUID sid = accessTokens.parseSession(pre.accessToken()).sessionId();
        var setup = totpService.beginSetup(user.getId());
        var enrolled = identity.enrollTotp(user.getId(), sid, totpService.generateCode(setup.secret(), Instant.now()));
        return enrolled.session().accessToken();
    }

    private static String hmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void filterCanonicalizesEncodedPathsProtectsPlatformAdminsAndBlocksCrossSiteRefresh() throws Exception {
        Organization organization = organizations.save(new Organization("Encoded Bypass Guard Org"));
        Project project = projects.save(new Project(organization.getId(), "Encoded Guard Project"));

        // 1. Percent-encoded /api/admin paths and /v1/chat/completions must never bypass filters
        mvc.perform(get(java.net.URI.create("/%61pi/admin/organizations")))
                .andExpect(status().isUnauthorized());

        AppUser member = identity.createUser("encoded-member@example.com", "StrongPassword123!", false, AccountTier.GENERAL_USER, null);
        identity.grantMembership(organization.getId(), member.getId(), OrganizationRole.DEVELOPER);
        projectMemberships.save(new ProjectMembership(project.getId(), member.getId(), ProjectMembership.Role.MEMBER, false));
        String memberJwt = loginVerifiedJwt(member);

        LlmService svc = services.save(new LlmService(organization.getId(), "enc-svc", "Enc Svc",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        LlmRequest reqRow = new LlmRequest("req_enc_bypass", project.getId(), null, member.getId(), svc, false);
        reqRow.succeed(null, 1, 1, 5L, 200, 0);
        llmRequests.saveAndFlush(reqRow);

        mvc.perform(get(java.net.URI.create("/api/admin/projects/" + project.getId() + "/requests/req_enc_bypass/%63ontent"))
                        .header("Authorization", "Bearer " + memberJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SENSITIVE_CONTENT_ACCESS_DENIED"));

        mvc.perform(post(java.net.URI.create("/v1/%63hat/completions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"enc-svc\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isUnauthorized());

        // 2. Non-platform ORGANIZATION_ADMIN cannot remove a SYSTEM_ADMIN from an organization
        AppUser orgAdmin = identity.createUser("org-admin-protect@example.com", "StrongPassword123!", false, AccountTier.GENERAL_USER, null);
        organizationMembers.save(new com.aiconnect.llmgateway.identity.OrganizationMember(organization.getId(), orgAdmin.getId(), OrganizationRole.ORGANIZATION_ADMIN));
        AppUser sysAdmin = identity.createUser("sys-admin-target@example.com", "StrongPassword123!", true, AccountTier.SYSTEM_ADMIN, null);
        identity.grantMembership(organization.getId(), sysAdmin.getId(), OrganizationRole.ORGANIZATION_ADMIN);
        String orgAdminJwt = loginVerifiedJwt(orgAdmin);

        mvc.perform(delete("/api/admin/organizations/" + organization.getId() + "/users/" + sysAdmin.getId())
                        .header("Authorization", "Bearer " + orgAdminJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_PROTECTED"));

        // 3. Cross-site Sec-Fetch-Site on /api/auth/refresh is rejected
        mvc.perform(post("/api/auth/refresh")
                        .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_MISMATCH"));

        // 4. LmStudioModelDiscovery redacts CLI args after boolean flags without index-skipping
        var discovery = new com.aiconnect.llmgateway.admin.LmStudioModelDiscovery(mapper);
        var modelInventory = mapper.readTree("{\"data\":[{\"id\":\"m1\",\"args\":[\"--auth\",\"--api-key\",\"sk-live-secret\",\"--host\",\"127.0.0.1\"]}]}");
        var discovered = discovery.discover(modelInventory, RuntimeType.LLAMA_CPP);
        assertThat(discovered.get(0).metadataJson()).doesNotContain("sk-live-secret");

        // 5. SecretCipher rejects malformed / wrong-nonce-length ciphertexts
        assertThatThrownBy(() -> secretCipher.decrypt("AAAA.BBBB"))
                .isInstanceOf(IllegalStateException.class);
    }
}
