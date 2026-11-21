package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.identity.AccessTokenService;
import com.aiconnect.llmgateway.identity.AppUser;
import com.aiconnect.llmgateway.identity.AppUserRepository;
import com.aiconnect.llmgateway.identity.AuthRefreshToken;
import com.aiconnect.llmgateway.identity.AuthRefreshTokenRepository;
import com.aiconnect.llmgateway.identity.OrganizationMember;
import com.aiconnect.llmgateway.identity.OrganizationMemberRepository;
import com.aiconnect.llmgateway.identity.OrganizationRole;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.quota.SpendQuota;
import com.aiconnect.llmgateway.quota.SpendQuotaRepository;
import com.aiconnect.llmgateway.quota.SpendQuotaScope;
import com.aiconnect.llmgateway.quota.SpendQuotaPeriod;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.team.TeamMember;
import com.aiconnect.llmgateway.team.TeamMemberRepository;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.team.TeamRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:aiconnect_role_usage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RoleScopedUsageIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired LlmServiceRepository services;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired LlmRequestRepository requests;
    @Autowired AppUserRepository users;
    @Autowired OrganizationMemberRepository organizationMembers;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired AccessTokenService tokens;
    @Autowired AuthRefreshTokenRepository sessions;
    @Autowired SpendQuotaRepository spendQuotas;

    @Test
    void derivesUsageFromTheLoginRoleWithoutAcceptingAProjectApiKey() throws Exception {
        Organization organization = organizations.save(new Organization("Role Usage Org"));
        Team ownedTeam = teams.save(new Team(organization.getId(), "Owned Team"));
        Team otherTeam = teams.save(new Team(organization.getId(), "Other Team"));

        AppUser issuer = user("issuer@usage.test", false);
        AppUser owner = user("owner@usage.test", false);
        AppUser otherIssuer = user("other@usage.test", false);
        AppUser administrator = user("admin@usage.test", false);
        member(organization, issuer, OrganizationRole.DEVELOPER);
        member(organization, owner, OrganizationRole.DEVELOPER);
        member(organization, otherIssuer, OrganizationRole.DEVELOPER);
        member(organization, administrator, OrganizationRole.ORGANIZATION_ADMIN);
        teamMembers.save(new TeamMember(ownedTeam.getId(), issuer.getId(), TeamRole.DEVELOPER));
        teamMembers.save(new TeamMember(ownedTeam.getId(), owner.getId(), TeamRole.PROJECT_OWNER));
        teamMembers.save(new TeamMember(ownedTeam.getId(), otherIssuer.getId(), TeamRole.DEVELOPER));

        Project ownedProject = projects.save(new Project(organization.getId(), ownedTeam.getId(), "Owned Project"));
        Project otherProject = projects.save(new Project(organization.getId(), otherTeam.getId(), "Other Project"));
        LlmService service = services.save(new LlmService(organization.getId(), "role-model", "Role Model",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));

        ApiKey issuerKey = apiKeys.save(new ApiKey(ownedProject.getId(), issuer.getId(), "issuer-key",
                "sk_llmg_role_issuer", "a".repeat(64), null));
        ApiKey teammateKey = apiKeys.save(new ApiKey(ownedProject.getId(), otherIssuer.getId(), "teammate-key",
                "sk_llmg_role_teammate", "b".repeat(64), null));
        ApiKey otherProjectKey = apiKeys.save(new ApiKey(otherProject.getId(), otherIssuer.getId(), "other-project-key",
                "sk_llmg_role_other", "c".repeat(64), null));

        saveRequest("issuer-request", ownedProject, issuerKey, issuer, service, 10);
        saveRequest("teammate-request", ownedProject, teammateKey, otherIssuer, service, 20);
        saveRequest("other-project-request", otherProject, otherProjectKey, otherIssuer, service, 30);

        apiKeys.delete(issuerKey);
        apiKeys.flush();
        ApiKey ownLiveKey = apiKeys.save(new ApiKey(ownedProject.getId(), issuer.getId(), "own-live", "sk_llmg_role_live", "d".repeat(64), null));
        spendQuotas.save(new SpendQuota(organization.getId(), SpendQuotaScope.API_KEY, ownLiveKey.getId(), "own limit", Currency.KRW, BigDecimal.TEN, SpendQuotaPeriod.MONTHLY, true));
        spendQuotas.save(new SpendQuota(organization.getId(), SpendQuotaScope.API_KEY, teammateKey.getId(), "other limit", Currency.KRW, BigDecimal.TEN, SpendQuotaPeriod.MONTHLY, true));
        spendQuotas.save(new SpendQuota(organization.getId(), SpendQuotaScope.ORGANIZATION, organization.getId(), "shared limit", Currency.KRW, BigDecimal.TEN, SpendQuotaPeriod.MONTHLY, true, true));

        String path = "/api/portal/organizations/{organizationId}/usage-overview";
        mvc.perform(get(path, organization.getId()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("KEY_ISSUER"))
                .andExpect(jsonPath("$.total.requestCount").value(1))
                .andExpect(jsonPath("$.total.inputTokens").value(10))
                .andExpect(jsonPath("$.runtimeAnalytics").doesNotExist())
                .andExpect(jsonPath("$.recentRequests[0].infrastructure").doesNotExist());

        mvc.perform(get(path, organization.getId()).header("Authorization", "Bearer " + accessToken(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PROJECT_OWNER"))
                .andExpect(jsonPath("$.total.requestCount").value(2))
                .andExpect(jsonPath("$.total.inputTokens").value(30))
                .andExpect(jsonPath("$.runtimeAnalytics").doesNotExist())
                .andExpect(jsonPath("$.availableProjects[0].access").value("PROJECT_ALL"));

        mvc.perform(get(path, organization.getId()).header("Authorization", "Bearer " + accessToken(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("ORGANIZATION"))
                .andExpect(jsonPath("$.total.requestCount").value(3))
                .andExpect(jsonPath("$.total.inputTokens").value(60))
                .andExpect(jsonPath("$.runtimeAnalytics.byServer[0].requests.requestCount").value(3))
                .andExpect(jsonPath("$.runtimeAnalytics.playground.total.requestCount").value(0));

        mvc.perform(get(path + "/requests", organization.getId()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value("issuer-request"))
                .andExpect(jsonPath("$[0].finalDeploymentId").doesNotExist());
        mvc.perform(get(path + "/requests", organization.getId()).param("projectId", otherProject.getId().toString()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "/requests/teammate-request", organization.getId()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "/requests/teammate-request", organization.getId()).header("Authorization", "Bearer " + accessToken(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finalDeploymentId").doesNotExist())
                .andExpect(jsonPath("$.diagnostic").doesNotExist());
        mvc.perform(get(path + "/own-key-quotas", organization.getId()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("own limit"));
        mvc.perform(get("/api/portal/projects/{projectId}/api-keys", ownedProject.getId()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("own-live"));
        mvc.perform(get("/api/portal/projects/{projectId}/api-keys", ownedProject.getId()).header("Authorization", "Bearer " + accessToken(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        String quotaPath = "/api/admin/projects/{projectId}/spend-quotas";
        mvc.perform(get(quotaPath, ownedProject.getId()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isForbidden());
        mvc.perform(get(quotaPath, ownedProject.getId()).header("Authorization", "Bearer " + accessToken(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ceilings[0].limitAmount").value(10))
                .andExpect(jsonPath("$.ceilings[0].usedAmount").doesNotExist());
        mvc.perform(get(quotaPath, ownedProject.getId()).header("X-Admin-Token", "integration-admin-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ceilings[0].usedAmount").exists());
        mvc.perform(get(path + "/own-key-quotas", organization.getId()).param("projectId", otherProject.getId().toString()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isForbidden());
        Organization foreign = organizations.save(new Organization("Foreign usage org"));
        mvc.perform(get(path + "/requests", foreign.getId()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isForbidden());
        teamMembers.findByIdTeamIdAndIdUserId(ownedTeam.getId(), issuer.getId()).ifPresent(teamMembers::delete);
        teamMembers.flush();
        mvc.perform(get(path + "/requests/issuer-request", organization.getId()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "/own-key-quotas", organization.getId()).header("Authorization", "Bearer " + accessToken(issuer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    private String accessToken(AppUser user) {
        var token = sessions.save(new AuthRefreshToken(user.getId(), java.util.UUID.randomUUID().toString(), java.time.Instant.now().plusSeconds(3600), true));
        return tokens.issue(user, token.getId(), true);
    }
    private AppUser user(String email, boolean platformAdmin) {
        AppUser created = new AppUser(email, "test-password-hash", platformAdmin);
        created.assignPendingTotpSecret("encrypted-test-secret");
        created.activateTotp(1L, java.time.Instant.now());
        return users.save(created);
    }

    private void member(Organization organization, AppUser user, OrganizationRole role) {
        organizationMembers.save(new OrganizationMember(organization.getId(), user.getId(), role));
    }

    private void saveRequest(String requestId, Project project, ApiKey key, AppUser issuer,
                             LlmService service, int inputTokens) {
        LlmRequest request = new LlmRequest(requestId, project.getId(), key.getId(), issuer.getId(), service, false);
        request.succeed(null, inputTokens, 1, 50, 200, 0);
        requests.save(request);
    }
}
