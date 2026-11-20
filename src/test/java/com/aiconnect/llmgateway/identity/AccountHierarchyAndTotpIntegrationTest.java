package com.aiconnect.llmgateway.identity;

import com.aiconnect.llmgateway.beta.BetaAdministration;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectExternalAccessRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.security.ConnectionRegistrationRequestRepository;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.team.TeamMemberRepository;
import com.aiconnect.llmgateway.team.TeamMember;
import com.aiconnect.llmgateway.team.TeamRole;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_hierarchy_totp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class AccountHierarchyAndTotpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TotpService totpService;
    @Autowired OrganizationRepository organizations;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired ProjectRepository projects;
    @Autowired LlmServiceRepository services;
    @Autowired BetaAdministration betaAdministration;
    @Autowired ProjectMembershipRepository projectMemberships;
    @Autowired RuntimeEndpointRepository runtimeEndpoints;
    @Autowired ExternalProviderRepository externalProviders;
    @Autowired ProjectExternalAccessRepository projectExternalAccess;
    @Autowired ConnectionRegistrationRequestRepository connectionRequests;

    @Test
    void enforcesThreeTierAccountHierarchyInvitationsMandatoryTotpAndCleanup() throws Exception {
        String adminEmail = "sysadmin@hierarchy.test";
        String adminPassword = "correct-horse-battery-staple";

        // 1. Bootstrap creates SYSTEM_ADMIN with mandatory TOTP onboarding
        MvcResult bootstrapRes = mvc.perform(post("/api/auth/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + adminEmail + "\",\"password\":\"" + adminPassword + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("ENROLLMENT_REQUIRED"))
                .andExpect(jsonPath("$.user.accountTier").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.user.totpEnabled").value(false))
                .andReturn();
        String adminPreMfaToken = objectMapper.readTree(bootstrapRes.getResponse().getContentAsString())
                .path("accessToken").asText();

        // Pre-TOTP session cannot access /api/admin/** or /api/portal/**
        mvc.perform(get("/api/admin/platform/users").header("Authorization", "Bearer " + adminPreMfaToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOTP_ENROLLMENT_REQUIRED"));
        mvc.perform(get("/api/portal/session").header("Authorization", "Bearer " + adminPreMfaToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOTP_ENROLLMENT_REQUIRED"));

        // X-Admin-Token break-glass remains unaffected
        mvc.perform(get("/api/admin/platform/overview").header("X-Admin-Token", "integration-admin-token"))
                .andExpect(status().isOk());

        // Complete TOTP enrollment for SYSTEM_ADMIN
        EnrolledSession adminSession = enrollTotp(adminPreMfaToken);
        String adminToken = adminSession.accessToken();

        // Setup organization, team, project (without team yet), and beta service
        Organization org = organizations.save(new Organization("Enterprise Org"));
        Organization otherOrg = organizations.save(new Organization("Other Enterprise Org"));
        Team team = teams.save(new Team(org.getId(), "Platform Team"));
        Project projectWithoutTeam = projects.save(new Project(org.getId(), "Direct Access Project"));
        Project otherProject = projects.save(new Project(org.getId(), "Other Project"));
        LlmService model = services.save(new LlmService(org.getId(), "local-qwen", "Local Qwen",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        var betaService = betaAdministration.create(org.getId(), model.getId());

        // SYSTEM_ADMIN cannot invite another SYSTEM_ADMIN
        mvc.perform(post("/api/admin/platform/account-invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"SYSTEM_ADMIN\",\"organizationId\":\"" + org.getId() + "\"}"))
                .andExpect(status().isForbidden());

        // 2. SYSTEM_ADMIN issues PROJECT_DIRECTOR invitation with betaServiceId & expectedEmail
        MvcResult directorInviteRes = mvc.perform(post("/api/admin/platform/account-invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"PROJECT_DIRECTOR\",\"organizationId\":\"" + org.getId()
                                + "\",\"projectId\":\"" + projectWithoutTeam.getId() + "\",\"betaServiceId\":\"" + betaService.getId()
                                + "\",\"expectedEmail\":\"director@hierarchy.test\",\"expiresInHours\":48}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.targetTier").value("PROJECT_DIRECTOR"))
                .andExpect(jsonPath("$.registrationPath").value(containsString("/#register?code=")))
                .andReturn();
        String directorInviteCode = objectMapper.readTree(directorInviteRes.getResponse().getContentAsString())
                .path("code").asText();

        // Inspect works publicly; invalid codes report valid=false
        mvc.perform(post("/api/auth/invitations/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + directorInviteCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.targetTier").value("PROJECT_DIRECTOR"))
                .andExpect(jsonPath("$.organizationName").value("Enterprise Org"))
                .andExpect(jsonPath("$.projectName").value("Direct Access Project"))
                .andExpect(jsonPath("$.expectedEmail").value("director@hierarchy.test"));

        mvc.perform(post("/api/auth/invitations/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"aci_nonexistent_code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));

        // Mismatched expectedEmail is rejected
        mvc.perform(post("/api/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + directorInviteCode + "\",\"email\":\"wrong@hierarchy.test\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INVITATION_EMAIL_MISMATCH"));

        // Accept as PROJECT_DIRECTOR -> returns ENROLLMENT_REQUIRED session
        MvcResult directorAcceptRes = mvc.perform(post("/api/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + directorInviteCode + "\",\"email\":\"director@hierarchy.test\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("ENROLLMENT_REQUIRED"))
                .andExpect(jsonPath("$.user.accountTier").value("PROJECT_DIRECTOR"))
                .andExpect(jsonPath("$.user.platformAdmin").value(false))
                .andReturn();
        JsonNode directorAcceptJson = objectMapper.readTree(directorAcceptRes.getResponse().getContentAsString());
        String directorUserId = directorAcceptJson.path("user").path("id").asText();
        EnrolledSession directorSession = enrollTotp(directorAcceptJson.path("accessToken").asText());
        String directorToken = directorSession.accessToken();

        // Consumed invitation cannot be reused
        mvc.perform(post("/api/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + directorInviteCode + "\",\"email\":\"director2@hierarchy.test\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INVITATION_INVALID"));

        // PROJECT_DIRECTOR received BetaOperator permission automatically
        mvc.perform(get("/api/beta/operator/services").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // A project manager can submit connection details without creating or contacting a runtime.
        long endpointsBefore = runtimeEndpoints.count();
        MvcResult submitted = mvc.perform(post("/api/portal/projects/{projectId}/connection-requests", projectWithoutTeam.getId())
                        .header("Authorization", "Bearer " + directorToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"RUNTIME\",\"displayName\":\"Requested GPU\",\"baseUrl\":\"http://100.106.74.36:4040\",\"runtimeType\":\"LLAMA_CPP\",\"secret\":\"private-token\",\"reason\":\"Project GPU\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.secret").doesNotExist()).andReturn();
        String connectionId = objectMapper.readTree(submitted.getResponse().getContentAsString()).path("id").asText();
        assertThat(runtimeEndpoints.count()).isEqualTo(endpointsBefore);
        mvc.perform(post("/api/admin/platform/connection-requests/{id}/decision", connectionId)
                        .header("Authorization", "Bearer " + directorToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"reason\":\"reviewed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/platform/connection-requests/{id}/decision", connectionId)
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"reason\":\"reviewed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.secret").doesNotExist());
        assertThat(runtimeEndpoints.count()).isEqualTo(endpointsBefore + 1);
        mvc.perform(post("/api/admin/platform/connection-requests/{id}/decision", connectionId)
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
        long providersBefore = externalProviders.count();
        MvcResult providerRequest = mvc.perform(post("/api/portal/projects/{projectId}/connection-requests", projectWithoutTeam.getId())
                        .header("Authorization", "Bearer " + directorToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXTERNAL_PROVIDER\",\"displayName\":\"Requested API\",\"baseUrl\":\"https://example.invalid/v1\",\"secret\":\"external-api-secret\",\"reason\":\"Testing\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.secret").doesNotExist()).andReturn();
        UUID providerRequestId = UUID.fromString(objectMapper.readTree(providerRequest.getResponse().getContentAsString()).path("id").asText());
        assertThat(externalProviders.count()).isEqualTo(providersBefore);
        assertThat(connectionRequests.findById(providerRequestId).orElseThrow().getEncryptedSecret()).doesNotContain("external-api-secret");
        mvc.perform(post("/api/admin/platform/connection-requests/{id}/decision", providerRequestId)
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"reason\":\"not approved\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(connectionRequests.findById(providerRequestId).orElseThrow().getEncryptedSecret()).isNull();
        assertThat(externalProviders.count()).isEqualTo(providersBefore);

        // Approved EXTERNAL_PROVIDER request creates ExternalProvider, wipes secret, and does NOT auto-grant ProjectExternalAccess
        MvcResult safeProviderReq = mvc.perform(post("/api/portal/projects/{projectId}/connection-requests", projectWithoutTeam.getId())
                        .header("Authorization", "Bearer " + directorToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXTERNAL_PROVIDER\",\"displayName\":\"Approved External API\",\"baseUrl\":\"https://api.example.invalid/v1\",\"secret\":\"approved-external-secret\",\"reason\":\"Vendor API\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.secret").doesNotExist()).andReturn();
        UUID safeProviderReqId = UUID.fromString(objectMapper.readTree(safeProviderReq.getResponse().getContentAsString()).path("id").asText());
        assertThat(connectionRequests.findById(safeProviderReqId).orElseThrow().getEncryptedSecret()).doesNotContain("approved-external-secret");
        MvcResult approvedProviderRes = mvc.perform(post("/api/admin/platform/connection-requests/{id}/decision", safeProviderReqId)
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"reason\":\"security approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andReturn();
        UUID createdProviderId = UUID.fromString(objectMapper.readTree(approvedProviderRes.getResponse().getContentAsString()).path("resultId").asText());
        assertThat(externalProviders.count()).isEqualTo(providersBefore + 1);
        assertThat(externalProviders.findById(createdProviderId)).isPresent();
        assertThat(connectionRequests.findById(safeProviderReqId).orElseThrow().getEncryptedSecret()).isNull();
        assertThat(projectExternalAccess.findByProjectIdAndProviderId(projectWithoutTeam.getId(), createdProviderId)).isEmpty();

        // SSRF URL on EXTERNAL_PROVIDER request is blocked on approval without creating an ExternalProvider
        MvcResult ssrfProviderReq = mvc.perform(post("/api/portal/projects/{projectId}/connection-requests", projectWithoutTeam.getId())
                        .header("Authorization", "Bearer " + directorToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"EXTERNAL_PROVIDER\",\"displayName\":\"SSRF API\",\"baseUrl\":\"http://169.254.169.254/latest/meta-data\",\"secret\":\"ssrf-secret\",\"reason\":\"Metadata probe\"}"))
                .andExpect(status().isOk()).andReturn();
        UUID ssrfProviderReqId = UUID.fromString(objectMapper.readTree(ssrfProviderReq.getResponse().getContentAsString()).path("id").asText());
        mvc.perform(post("/api/admin/platform/connection-requests/{id}/decision", ssrfProviderReqId)
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"reason\":\"attempt ssrf\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXTERNAL_PROVIDER_URL_FORBIDDEN"));
        assertThat(externalProviders.count()).isEqualTo(providersBefore + 1);
        mvc.perform(post("/api/admin/platform/connection-requests/{id}/decision", ssrfProviderReqId)
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"reason\":\"blocked ssrf\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(connectionRequests.findById(ssrfProviderReqId).orElseThrow().getEncryptedSecret()).isNull();

        // 3. PROJECT_DIRECTOR cannot issue PROJECT_DIRECTOR or cross-org invitations
        mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"teamRole\":\"TEAM_ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INVITATION_ROLE_FORBIDDEN"));

        mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"PROJECT_DIRECTOR\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_TIER_FORBIDDEN"));

        mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", otherOrg.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"projectId\":\"" + projectWithoutTeam.getId() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_SCOPE_REQUIRED"));

        // PROJECT_DIRECTOR issues GENERAL_USER invitation for projectWithoutTeam
        mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"projectId\":\"" + otherProject.getId() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PROJECT_ACCESS_DENIED"));
        mvc.perform(get("/api/admin/projects/{id}/api-keys", otherProject.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/platform/projects/{projectId}/managers/{userId}", otherProject.getId(), directorUserId)
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/platform/projects/{projectId}/managers/{userId}", otherProject.getId(), directorUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/projects/{id}/api-keys", otherProject.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk());
        MvcResult generalInviteRes = mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"projectId\":\"" + projectWithoutTeam.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTier").value("GENERAL_USER"))
                .andReturn();
        String generalInviteCode = objectMapper.readTree(generalInviteRes.getResponse().getContentAsString())
                .path("code").asText();

        // A director must not inspect or revoke invitations issued by another administrator.
        MvcResult otherInvite = mvc.perform(post("/api/admin/platform/account-invitations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"organizationId\":\"" + org.getId() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String otherInviteId = objectMapper.readTree(otherInvite.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(get("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations/{id}/revoke", org.getId(), otherInviteId)
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isForbidden());

        // Concurrent accept race on single-use invitation: exactly 1 succeeds under PESSIMISTIC_WRITE lock
        MvcResult raceInviteRes = mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"projectId\":\"" + projectWithoutTeam.getId() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String raceCode = objectMapper.readTree(raceInviteRes.getResponse().getContentAsString()).path("code").asText();
        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String email = "race" + i + "@hierarchy.test";
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mvc.perform(post("/api/auth/invitations/accept")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"code\":\"" + raceCode + "\",\"email\":\"" + email + "\",\"password\":\"correct-horse-battery-staple\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            executor.shutdownNow();
        }

        // GENERAL_USER accepts project invitation and completes TOTP enrollment
        MvcResult generalAcceptRes = mvc.perform(post("/api/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + generalInviteCode + "\",\"email\":\"dev@hierarchy.test\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.accountTier").value("GENERAL_USER"))
                .andExpect(jsonPath("$.mfaState").value("ENROLLMENT_REQUIRED"))
                .andReturn();
        JsonNode generalAcceptJson = objectMapper.readTree(generalAcceptRes.getResponse().getContentAsString());
        String generalUserId = generalAcceptJson.path("user").path("id").asText();
        assertThat(projectMemberships.findById(new ProjectMembershipId(projectWithoutTeam.getId(), UUID.fromString(generalUserId))))
                .get().extracting(ProjectMembership::getRole).isEqualTo(ProjectMembership.Role.MEMBER);
        EnrolledSession generalSession = enrollTotp(generalAcceptJson.path("accessToken").asText());
        String generalToken = generalSession.accessToken();

        // GENERAL_USER sees Developer Portal session, assigned project, and cannot invite others
        mvc.perform(get("/api/portal/session").header("Authorization", "Bearer " + generalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountTier").value("GENERAL_USER"))
                .andExpect(jsonPath("$.totpEnabled").value(true))
                .andExpect(jsonPath("$.mfaState").value("VERIFIED"))
                .andExpect(jsonPath("$.memberships[0].role").value("DEVELOPER"));

        mvc.perform(get("/api/portal/organizations/{orgId}/projects", org.getId())
                        .header("Authorization", "Bearer " + generalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(projectWithoutTeam.getId().toString()));

        // Losing the last explicit project assignment must never restore legacy team privileges.
        teamMembers.saveAndFlush(new TeamMember(team.getId(), UUID.fromString(generalUserId), TeamRole.TEAM_ADMIN));
        projectMemberships.deleteById(new ProjectMembershipId(projectWithoutTeam.getId(), UUID.fromString(generalUserId)));
        projectMemberships.flush();
        mvc.perform(get("/api/admin/organizations/{orgId}/teams/{teamId}/members", org.getId(), team.getId())
                        .header("Authorization", "Bearer " + generalToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/portal/organizations/{orgId}/projects", org.getId())
                        .header("Authorization", "Bearer " + generalToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        projectMemberships.saveAndFlush(new ProjectMembership(projectWithoutTeam.getId(), UUID.fromString(generalUserId), ProjectMembership.Role.MEMBER));

        mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + generalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\"}"))
                .andExpect(status().isForbidden());

        // 4. TOTP Login -> Refresh preserves mfaVerified=false -> Verify upgrades -> Replay blocked -> Recovery code works -> 5 failures lock
        MvcResult generalLogin = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@hierarchy.test\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("VERIFICATION_REQUIRED"))
                .andReturn();
        Cookie preVerifyCookie = generalLogin.getResponse().getCookie("aiconnect_refresh");
        String pendingToken = objectMapper.readTree(generalLogin.getResponse().getContentAsString())
                .path("accessToken").asText();

        mvc.perform(get("/api/portal/session").header("Authorization", "Bearer " + pendingToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOTP_VERIFICATION_REQUIRED"));

        MvcResult preVerifyRefresh = mvc.perform(post("/api/auth/refresh").cookie(preVerifyCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("VERIFICATION_REQUIRED"))
                .andReturn();
        String refreshedPendingToken = objectMapper.readTree(preVerifyRefresh.getResponse().getContentAsString())
                .path("accessToken").asText();

        String verifyCode = totpService.generateCode(generalSession.secret(), Instant.now().plusSeconds(30));
        MvcResult verifyRes = mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + refreshedPendingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + verifyCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("VERIFIED"))
                .andReturn();
        Cookie verifiedCookie = verifyRes.getResponse().getCookie("aiconnect_refresh");
        String verifiedGeneralToken = objectMapper.readTree(verifyRes.getResponse().getContentAsString())
                .path("accessToken").asText();

        // Refresh after MFA preserves VERIFIED state
        MvcResult verifiedRefresh = mvc.perform(post("/api/auth/refresh").cookie(verifiedCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("VERIFIED"))
                .andReturn();
        String postRefreshToken = objectMapper.readTree(verifiedRefresh.getResponse().getContentAsString())
                .path("accessToken").asText();
        mvc.perform(get("/api/portal/session").header("Authorization", "Bearer " + postRefreshToken))
                .andExpect(status().isOk());

        // Replay same TOTP step is blocked
        MvcResult secondLogin = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@hierarchy.test\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String secondPending = objectMapper.readTree(secondLogin.getResponse().getContentAsString())
                .path("accessToken").asText();
        mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + secondPending)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + verifyCode + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOTP_CODE_ALREADY_USED"));

        // Recovery code works once on login and is consumed
        String recovery = generalSession.recoveryCodes().get(0);
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@hierarchy.test\",\"password\":\"correct-horse-battery-staple\",\"recoveryCode\":\"" + recovery + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("VERIFIED"));
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@hierarchy.test\",\"password\":\"correct-horse-battery-staple\",\"recoveryCode\":\"" + recovery + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOTP_CODE"));

        // 3 more failures reach 5 total consecutive failures and trigger TOTP_LOCKED (429)
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/totp/verify")
                            .header("Authorization", "Bearer " + secondPending)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"000000\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + secondPending)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOTP_LOCKED"));

        // 5. Tier-scoped TOTP reset & admin views
        // PROJECT_DIRECTOR cannot reset own or SYSTEM_ADMIN TOTP
        mvc.perform(post("/api/admin/organizations/{orgId}/users/{userId}/totp-reset", org.getId(), directorUserId)
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_ADMIN_REQUIRED"));

        // SYSTEM_ADMIN can reset GENERAL_USER TOTP -> revokes active sessions and requires re-enrollment
        mvc.perform(post("/api/admin/organizations/{orgId}/users/{userId}/totp-reset", org.getId(), generalUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/portal/session").header("Authorization", "Bearer " + postRefreshToken))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@hierarchy.test\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaState").value("ENROLLMENT_REQUIRED"))
                .andExpect(jsonPath("$.user.totpEnabled").value(false));

        // OrganizationUserView & PlatformUserView expose accountTier and totpEnabled
        mvc.perform(get("/api/admin/organizations/{orgId}/users", org.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email=='dev@hierarchy.test')].accountTier").value("GENERAL_USER"))
                .andExpect(jsonPath("$[?(@.email=='dev@hierarchy.test')].totpEnabled").value(false));

        mvc.perform(get("/api/admin/platform/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email=='director@hierarchy.test')].accountTier").value("PROJECT_DIRECTOR"))
                .andExpect(jsonPath("$[?(@.email=='director@hierarchy.test')].totpEnabled").value(true));

        // SYSTEM_ADMIN can reset PROJECT_DIRECTOR TOTP and delete users/organizations cleanly
        mvc.perform(post("/api/admin/platform/users/{userId}/totp-reset", directorUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/admin/platform/users/{userId}", generalUserId)
                        .param("confirmation", "dev@hierarchy.test")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/admin/platform/organizations/{orgId}", org.getId())
                        .param("confirmation", "Enterprise Org")
                        .param("purgeHistory", "true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    private EnrolledSession enrollTotp(String preMfaToken) throws Exception {
        String setupJson = mvc.perform(post("/api/auth/totp/setup")
                        .header("Authorization", "Bearer " + preMfaToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        JsonNode setupNode = objectMapper.readTree(setupJson);
        String secret = setupNode.path("secret").asText();
        String code = totpService.generateCode(secret, Instant.now());

        String enrollJson = mvc.perform(post("/api/auth/totp/enroll")
                        .header("Authorization", "Bearer " + preMfaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.session.mfaState").value("VERIFIED"))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(8))
                .andReturn().getResponse().getContentAsString();
        JsonNode enrollNode = objectMapper.readTree(enrollJson);
        List<String> recovery = new ArrayList<>();
        enrollNode.path("recoveryCodes").forEach(item -> recovery.add(item.asText()));
        return new EnrolledSession(enrollNode.path("session").path("accessToken").asText(), secret, recovery);
    }

    private record EnrolledSession(String accessToken, String secret, List<String> recoveryCodes) { }
}
