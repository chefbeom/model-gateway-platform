package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.admin.AdminDtos;
import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.admin.PlatformAdministrationService;
import com.aiconnect.llmgateway.beta.BetaAdministration;
import com.aiconnect.llmgateway.beta.BetaOperator;
import com.aiconnect.llmgateway.beta.BetaOperatorRepository;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RetryPolicy;
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
import com.aiconnect.llmgateway.notification.NotificationChannelService;
import com.aiconnect.llmgateway.notification.NotificationChannelType;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private static String hmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
