package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.ApiKeyStatus;
import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.DataBoundaryPolicy;
import com.aiconnect.llmgateway.domain.ExternalAccessStatus;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.ExternalProviderType;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.ProjectExternalAccess;
import com.aiconnect.llmgateway.domain.ProjectServiceAccess;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.domain.ServiceTarget;
import com.aiconnect.llmgateway.identity.AccessTokenService;
import com.aiconnect.llmgateway.identity.AccountInvitationRepository;
import com.aiconnect.llmgateway.identity.AccountTier;
import com.aiconnect.llmgateway.identity.AppUser;
import com.aiconnect.llmgateway.identity.AppUserRepository;
import com.aiconnect.llmgateway.identity.AuthRefreshToken;
import com.aiconnect.llmgateway.identity.AuthRefreshTokenRepository;
import com.aiconnect.llmgateway.identity.IdentityService;
import com.aiconnect.llmgateway.identity.OrganizationRole;
import com.aiconnect.llmgateway.identity.ProjectMembership;
import com.aiconnect.llmgateway.identity.ProjectMembershipId;
import com.aiconnect.llmgateway.identity.ProjectMembershipRepository;
import com.aiconnect.llmgateway.identity.TotpService;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectExternalAccessRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.ProjectServiceAccessRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.repository.ServiceTargetRepository;
import com.aiconnect.llmgateway.security.ProjectRuntimeApprovalRepository;
import com.aiconnect.llmgateway.security.RuntimeTrustAttestation;
import com.aiconnect.llmgateway.security.RuntimeTrustAttestationRepository;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.team.Team;
import com.aiconnect.llmgateway.team.TeamMember;
import com.aiconnect.llmgateway.team.TeamMemberRepository;
import com.aiconnect.llmgateway.team.TeamRepository;
import com.aiconnect.llmgateway.team.TeamRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
        "spring.datasource.url=jdbc:h2:mem:aiconnect_governance_policy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "aiconnect.security.external-url.allow-loopback-test-server=true"
})
class PolicyIntersectionAndGovernanceIntegrationTest {
    private static final String ADMIN_TOKEN = "integration-admin-token";
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OrganizationRepository organizations;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository teamMembers;
    @Autowired ProjectRepository projects;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired ExternalProviderRepository providers;
    @Autowired ProjectExternalAccessRepository externalAccess;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired ProjectServiceAccessRepository serviceAccess;
    @Autowired LlmRequestRepository requests;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired ApiKeyService apiKeyService;
    @Autowired SecretCipher cipher;
    @Autowired IdentityService identity;
    @Autowired AppUserRepository users;
    @Autowired AccessTokenService accessTokens;
    @Autowired AuthRefreshTokenRepository refreshTokens;
    @Autowired TotpService totpService;
    @Autowired ProjectMembershipRepository projectMemberships;
    @Autowired AccountInvitationRepository accountInvitations;
    @Autowired RuntimeTrustAttestationRepository runtimeTrust;
    @Autowired ProjectRuntimeApprovalRepository runtimeApprovals;

    @Test
    void projectExternalNoRetentionAndApiKeyInternalOnlyIntersection() throws Exception {
        AtomicInteger internalCalls = new AtomicInteger();
        AtomicInteger noRetentionCalls = new AtomicInteger();
        AtomicInteger commercialCalls = new AtomicInteger();
        try (var servers = new MockServers()) {
            HttpServer internalServer = servers.start(internalCalls);
            HttpServer noRetentionServer = servers.start(noRetentionCalls);
            HttpServer commercialServer = servers.start(commercialCalls);

            Organization org = organizations.save(new Organization("Intersection Org " + UUID.randomUUID()));
            Project docProject = projects.save(new Project(org.getId(), "문서 분석"));
            mvc.perform(patch("/api/admin/projects/{id}", docProject.getId())
                            .header("X-Admin-Token", ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"문서 분석\",\"dataBoundaryPolicy\":\"APPROVED_EXTERNAL_NO_RETENTION\",\"maxDataBoundaryPolicy\":\"APPROVED_EXTERNAL_NO_RETENTION\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dataBoundaryPolicy").value("APPROVED_EXTERNAL_NO_RETENTION"))
                    .andExpect(jsonPath("$.maxDataBoundaryPolicy").value("APPROVED_EXTERNAL_NO_RETENTION"))
                    .andExpect(jsonPath("$.externalAiBlocked").value(false));

            InferenceNode node = nodes.save(new InferenceNode(org.getId(), "gpu-node", null, "DIRECT", null));
            RuntimeEndpoint internalEp = saveEndpoint(node, "internal-gpu", internalServer);
            RuntimeEndpoint noRetentionEp = saveEndpoint(node, "no-retention-gpu", noRetentionServer);
            classifyAndApproveInternal(docProject.getId(), internalEp.getId());
            classifyAndApproveNoRetention(docProject.getId(), noRetentionEp.getId());

            ModelDeployment internalModel = deployments.save(new ModelDeployment(internalEp.getId(), "internal-llm", "compat", "Internal LLM", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));
            ModelDeployment noRetentionModel = deployments.save(new ModelDeployment(noRetentionEp.getId(), "ext-gpu-llm", "compat", "No-Retention GPU", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));

            ExternalProvider provider = new ExternalProvider(org.getId(), ExternalProviderType.OPENAI, "Commercial OpenAI",
                    "http://127.0.0.1:" + commercialServer.getAddress().getPort() + "/v1", cipher.encrypt("ext-secret"));
            provider.recordHealth(true);
            provider = providers.save(provider);
            ModelDeployment commercialModel = deployments.save(ModelDeployment.external(provider.getId(), "gpt-4o", "compat",
                    "Commercial GPT", 8192, 10, "[\"CHAT_COMPLETION\"]", BigDecimal.ONE, BigDecimal.ONE, Currency.KRW));
            ProjectExternalAccess access = new ProjectExternalAccess(docProject.getId(), provider.getId(), null, "approved");
            access.decide(ExternalAccessStatus.APPROVED, true, true, null, null, null);
            externalAccess.save(access);

            // Service 1: commercial API + no-retention GPU (no internal GPU)
            LlmService noRetentionService = services.save(new LlmService(org.getId(), "doc-analysis", "Doc Analysis",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            targets.save(new ServiceTarget(noRetentionService.getId(), commercialModel.getId(), 1, 100, false, null));
            targets.save(new ServiceTarget(noRetentionService.getId(), noRetentionModel.getId(), 2, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(docProject.getId(), noRetentionService.getId()));

            // Service 2: commercial API + no-retention GPU + internal GPU
            LlmService hybridService = services.save(new LlmService(org.getId(), "hybrid-analysis", "Hybrid Analysis",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            targets.save(new ServiceTarget(hybridService.getId(), commercialModel.getId(), 1, 100, false, null));
            targets.save(new ServiceTarget(hybridService.getId(), noRetentionModel.getId(), 2, 100, false, null));
            targets.save(new ServiceTarget(hybridService.getId(), internalModel.getId(), 3, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(docProject.getId(), hybridService.getId()));

            String regularKeyJson = mvc.perform(post("/api/admin/projects/{id}/api-keys", docProject.getId())
                            .header("X-Admin-Token", ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"regular-doc-key\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String regularSecret = mapper.readTree(regularKeyJson).path("secret").asText();

            String hrKeyJson = mvc.perform(post("/api/admin/projects/{id}/api-keys", docProject.getId())
                            .header("X-Admin-Token", ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"인사 문서\",\"dataBoundaryPolicy\":\"INTERNAL_ONLY\"}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String hrSecret = mapper.readTree(hrKeyJson).path("secret").asText();

            // Regular key with clean prompt -> commercial API blocked by APPROVED_EXTERNAL_NO_RETENTION; routes to no-retention GPU
            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + regularSecret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"doc-analysis\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"clean document summary\"}]}"))
                    .andExpect(status().isOk());
            assertThat(commercialCalls).hasValue(0);
            assertThat(noRetentionCalls).hasValue(1);

            // HR key (INTERNAL_ONLY) with clean prompt on no-retention-only service -> blocked completely!
            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + hrSecret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"doc-analysis\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"clean hr memo\"}]}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("LOCAL_MODEL_UNAVAILABLE"));
            assertThat(noRetentionCalls).hasValue(1);
            assertThat(commercialCalls).hasValue(0);

            // HR key (INTERNAL_ONLY) on hybrid service -> routes strictly to INTERNAL GPU
            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + hrSecret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"hybrid-analysis\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"clean hr memo\"}]}"))
                    .andExpect(status().isOk());
            assertThat(internalCalls).hasValue(1);
            assertThat(noRetentionCalls).hasValue(1);
            assertThat(commercialCalls).hasValue(0);
            LlmRequest hrTrace = requests.findTop50ByProjectIdOrderByStartedAtDesc(docProject.getId()).get(0);
            assertThat(hrTrace.getDataExternalAllowed()).isFalse();
        }
    }

    @Test
    void logicalServiceInternalOnlyOverridesPermissiveProjectAndApiKey() throws Exception {
        AtomicInteger internalCalls = new AtomicInteger();
        AtomicInteger noRetentionCalls = new AtomicInteger();
        AtomicInteger commercialCalls = new AtomicInteger();
        try (var servers = new MockServers()) {
            HttpServer internalServer = servers.start(internalCalls);
            HttpServer noRetentionServer = servers.start(noRetentionCalls);
            HttpServer commercialServer = servers.start(commercialCalls);

            Organization org = organizations.save(new Organization("Service Boundary Org " + UUID.randomUUID()));
            Project openProject = projects.save(new Project(org.getId(), "open-project"));
            InferenceNode node = nodes.save(new InferenceNode(org.getId(), "node", null, "DIRECT", null));
            RuntimeEndpoint internalEp = saveEndpoint(node, "internal", internalServer);
            RuntimeEndpoint noRetentionEp = saveEndpoint(node, "no-ret", noRetentionServer);
            classifyAndApproveInternal(openProject.getId(), internalEp.getId());
            classifyAndApproveNoRetention(openProject.getId(), noRetentionEp.getId());

            ModelDeployment internalModel = deployments.save(new ModelDeployment(internalEp.getId(), "int-m", "compat", "Int", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));
            ModelDeployment noRetentionModel = deployments.save(new ModelDeployment(noRetentionEp.getId(), "ext-m", "compat", "Ext", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));
            ExternalProvider provider = new ExternalProvider(org.getId(), ExternalProviderType.OPENAI, "Cloud",
                    "http://127.0.0.1:" + commercialServer.getAddress().getPort() + "/v1", cipher.encrypt("k"));
            provider.recordHealth(true);
            provider = providers.save(provider);
            ModelDeployment commercialModel = deployments.save(ModelDeployment.external(provider.getId(), "gpt", "compat",
                    "GPT", 8192, 10, "[\"CHAT_COMPLETION\"]", BigDecimal.ONE, BigDecimal.ONE, Currency.KRW));
            ProjectExternalAccess access = new ProjectExternalAccess(openProject.getId(), provider.getId(), null, "ok");
            access.decide(ExternalAccessStatus.APPROVED, true, true, null, null, null);
            externalAccess.save(access);

            LlmService service = services.save(new LlmService(org.getId(), "strict-service", "Strict Service",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            mvc.perform(patch("/api/admin/services/{id}/policy", service.getId())
                            .header("X-Admin-Token", ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dataBoundaryPolicy\":\"INTERNAL_ONLY\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.dataBoundaryPolicy").value("INTERNAL_ONLY"));

            targets.save(new ServiceTarget(service.getId(), commercialModel.getId(), 1, 100, false, null));
            targets.save(new ServiceTarget(service.getId(), noRetentionModel.getId(), 2, 100, false, null));
            targets.save(new ServiceTarget(service.getId(), internalModel.getId(), 3, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(openProject.getId(), service.getId()));

            String keySecret = apiKeyService.issue(openProject.getId(), "open-key", null, null, DataBoundaryPolicy.EXTERNAL_ALLOWED).secret();

            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + keySecret)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"strict-service\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"clean prompt\"}]}"))
                    .andExpect(status().isOk());
            assertThat(commercialCalls).hasValue(0);
            assertThat(noRetentionCalls).hasValue(0);
            assertThat(internalCalls).hasValue(1);
        }
    }

    @Test
    void nonRelaxationEnforcedForProjectDirectorAndApiKeysAndDlp() throws Exception {
        Organization org = organizations.save(new Organization("Non-Relaxation Org " + UUID.randomUUID()));
        Project project = projects.save(new Project(org.getId(), "Bounded Project"));
        AppUser director = createDirector(org, project, "director-nr@gov.test");
        String directorToken = sessionToken(director);

        // Admin sets ceiling = APPROVED_EXTERNAL_NO_RETENTION and org DLP = ENFORCE LOCAL_ONLY
        mvc.perform(patch("/api/admin/projects/{id}", project.getId())
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bounded Project\",\"dataBoundaryPolicy\":\"APPROVED_EXTERNAL_NO_RETENTION\",\"maxDataBoundaryPolicy\":\"APPROVED_EXTERNAL_NO_RETENTION\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/organizations/{orgId}/data-protection", org.getId())
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"ENFORCE\",\"level\":\"STRICT\",\"externalAction\":\"LOCAL_ONLY\",\"allowExternalFailover\":false,\"detectPii\":true}"))
                .andExpect(status().isOk());

        // Director cannot relax project above maxDataBoundaryPolicy or change ceiling
        mvc.perform(patch("/api/admin/projects/{id}", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bounded Project\",\"dataBoundaryPolicy\":\"EXTERNAL_ALLOWED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DATA_BOUNDARY_RELAXATION_DENIED"));

        mvc.perform(patch("/api/admin/projects/{id}", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bounded Project\",\"maxDataBoundaryPolicy\":\"EXTERNAL_ALLOWED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DATA_BOUNDARY_RELAXATION_DENIED"));

        // Director cannot create or patch API key above project effective boundary
        mvc.perform(post("/api/admin/projects/{id}/api-keys", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"too-open\",\"dataBoundaryPolicy\":\"EXTERNAL_ALLOWED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DATA_BOUNDARY_RELAXATION_DENIED"));

        String keyJson = mvc.perform(post("/api/admin/projects/{id}/api-keys", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"valid-key\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String keyId = mapper.readTree(keyJson).path("id").asText();

        mvc.perform(patch("/api/admin/projects/{id}/api-keys/{keyId}/policy", project.getId(), keyId)
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataBoundaryPolicy\":\"EXTERNAL_ALLOWED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DATA_BOUNDARY_RELAXATION_DENIED"));

        // Director CAN tighten API key to INTERNAL_ONLY
        mvc.perform(patch("/api/admin/projects/{id}/api-keys/{keyId}/policy", project.getId(), keyId)
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataBoundaryPolicy\":\"INTERNAL_ONLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataBoundaryPolicy").value("INTERNAL_ONLY"))
                .andExpect(jsonPath("$.effectiveDataBoundaryPolicy").value("INTERNAL_ONLY"));

        // Director cannot relax org DLP policy at project scope, and cannot access org-wide endpoints
        mvc.perform(put("/api/admin/projects/{id}/data-protection", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"OFF\",\"level\":\"RELAXED\",\"externalAction\":\"ALLOW\",\"allowExternalFailover\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DATA_PROTECTION_RELAXATION_DENIED"));
        mvc.perform(get("/api/admin/organizations/{orgId}/data-protection", org.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/organizations/{orgId}/services", org.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isForbidden());

        // Director CAN tighten project boundary to INTERNAL_ONLY, after which API key cannot use APPROVED_EXTERNAL_NO_RETENTION
        mvc.perform(patch("/api/admin/projects/{id}", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bounded Project\",\"dataBoundaryPolicy\":\"INTERNAL_ONLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalAiBlocked").value(true));
        mvc.perform(post("/api/admin/projects/{id}/api-keys", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"no-ret-after-lock\",\"dataBoundaryPolicy\":\"APPROVED_EXTERNAL_NO_RETENTION\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DATA_BOUNDARY_RELAXATION_DENIED"));
    }

    @Test
    void apiKeyDelegationToggleControlsGeneralUserIssuance() throws Exception {
        Organization org = organizations.save(new Organization("Delegation Org " + UUID.randomUUID()));
        Project project = projects.save(new Project(org.getId(), "Delegation Project"));
        AppUser director = createDirector(org, project, "director-del@gov.test");
        String directorToken = sessionToken(director);

        String inviteJson = mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"projectId\":\"" + project.getId() + "\",\"canIssueApiKeys\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canIssueApiKeys").value(false))
                .andReturn().getResponse().getContentAsString();
        String code = mapper.readTree(inviteJson).path("code").asText();

        EnrolledAccount member = acceptAndEnroll("member-del@gov.test", code);

        // Portal reports canIssueApiKeys = false and blocks issuance
        mvc.perform(get("/api/portal/organizations/{orgId}/projects", org.getId())
                        .header("Authorization", "Bearer " + member.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canIssueApiKeys").value(false));
        mvc.perform(post("/api/portal/projects/{id}/api-keys", project.getId())
                        .header("Authorization", "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"blocked-key\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("API_KEY_ISSUANCE_NOT_DELEGATED"));

        // Director sees member and grants delegation
        mvc.perform(get("/api/admin/projects/{id}/members", project.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId=='" + member.userId() + "')].canIssueApiKeys").value(false));
        mvc.perform(patch("/api/admin/projects/{id}/members/{uid}", project.getId(), member.userId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canIssueApiKeys\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canIssueApiKeys").value(true));

        mvc.perform(get("/api/portal/organizations/{orgId}/projects", org.getId())
                        .header("Authorization", "Bearer " + member.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canIssueApiKeys").value(true));
        mvc.perform(post("/api/portal/projects/{id}/api-keys", project.getId())
                        .header("Authorization", "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"delegated-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("delegated-key"));

        // Revoking delegation blocks issuance again
        mvc.perform(patch("/api/admin/projects/{id}/members/{uid}", project.getId(), member.userId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canIssueApiKeys\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canIssueApiKeys").value(false));
        mvc.perform(post("/api/portal/projects/{id}/api-keys", project.getId())
                        .header("Authorization", "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"blocked-again\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("API_KEY_ISSUANCE_NOT_DELEGATED"));
    }

    @Test
    void relationshipShowsTopTierOrganizationManagersAndMembers() throws Exception {
        Organization org = organizations.save(new Organization("Relationship Org " + UUID.randomUUID()));
        Project projectA = projects.save(new Project(org.getId(), "Rel Project A"));
        projects.save(new Project(org.getId(), "Rel Project B"));

        AppUser sysAdmin = identity.createUser("sysadmin-rel@gov.test", PASSWORD, true, AccountTier.SYSTEM_ADMIN, null);
        String adminToken = sessionToken(sysAdmin);
        AppUser director = createDirector(org, projectA, "director-rel@gov.test");
        String directorToken = sessionToken(director);
        String inviteCode = issueInvite(org.getId(), projectA.getId(), directorToken);
        EnrolledAccount member = acceptAndEnroll("member-rel@gov.test", inviteCode);

        mvc.perform(get("/api/portal/organizations/{orgId}/relationship", org.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName").value(org.getName()))
                .andExpect(jsonPath("$.topUsers[?(@.email=='sysadmin-rel@gov.test')].role").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.topUsers[?(@.email=='sysadmin-rel@gov.test')].currentUser").value(true))
                .andExpect(jsonPath("$.projects.length()").value(2))
                .andExpect(jsonPath("$.projects[?(@.name=='Rel Project A')].managers[0].email").value("director-rel@gov.test"))
                .andExpect(jsonPath("$.projects[?(@.name=='Rel Project A')].managers[0].role").value("MANAGER"))
                .andExpect(jsonPath("$.projects[?(@.name=='Rel Project A')].managers[0].roleLabel").value("프로젝트 총관리자"))
                .andExpect(jsonPath("$.projects[?(@.name=='Rel Project A')].members[0].email").value("member-rel@gov.test"))
                .andExpect(jsonPath("$.projects[?(@.name=='Rel Project A')].members[0].role").value("MEMBER"))
                .andExpect(jsonPath("$.projects[?(@.name=='Rel Project A')].members[0].roleLabel").value("팀원"));

        mvc.perform(get("/api/portal/organizations/{orgId}/relationship", org.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topUsers[?(@.email=='sysadmin-rel@gov.test')].role").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].name").value("Rel Project A"))
                .andExpect(jsonPath("$.projects[0].managers[0].email").value("director-rel@gov.test"))
                .andExpect(jsonPath("$.projects[0].managers[0].currentUser").value(true))
                .andExpect(jsonPath("$.projects[0].members[0].email").value("member-rel@gov.test"));

        mvc.perform(get("/api/portal/organizations/{orgId}/relationship", org.getId())
                        .header("Authorization", "Bearer " + member.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topUsers[?(@.email=='sysadmin-rel@gov.test')].role").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].name").value("Rel Project A"))
                .andExpect(jsonPath("$.projects[0].managers[0].email").value("director-rel@gov.test"))
                .andExpect(jsonPath("$.projects[0].members[0].email").value("member-rel@gov.test"))
                .andExpect(jsonPath("$.projects[0].members[0].currentUser").value(true));
    }

    @Test
    void managerAndMemberRevocationCascadesKeysInvitationsScopeAndTokens() throws Exception {
        Organization org = organizations.save(new Organization("Revoke Org " + UUID.randomUUID()));
        Team team = teams.save(new Team(org.getId(), "Legacy Team"));
        Project projectA = projects.save(new Project(org.getId(), team.getId(), "Project A"));
        Project projectB = projects.save(new Project(org.getId(), "Project B"));
        LlmService svc = services.save(new LlmService(org.getId(), "rev-svc-" + UUID.randomUUID().toString().substring(0, 6),
                "Rev Service", FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        serviceAccess.save(new ProjectServiceAccess(projectA.getId(), svc.getId()));
        serviceAccess.save(new ProjectServiceAccess(projectB.getId(), svc.getId()));

        // Enroll director via real invite/login so director has refresh cookie & TOTP
        String dirInviteRes = mvc.perform(post("/api/admin/platform/account-invitations")
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"PROJECT_DIRECTOR\",\"organizationId\":\"" + org.getId() + "\",\"projectId\":\"" + projectA.getId() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        EnrolledAccount director = acceptAndEnroll("dir-rev@gov.test", mapper.readTree(dirInviteRes).path("code").asText());
        // Assign director also to team (legacy) and Project B
        teamMembers.save(new TeamMember(team.getId(), director.userId(), TeamRole.TEAM_ADMIN));
        mvc.perform(put("/api/admin/platform/projects/{pid}/managers/{uid}", projectB.getId(), director.userId())
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk());

        // Director issues API keys on Project A and Project B, plus a pending invitation on Project A
        String dirKeyARes = mvc.perform(post("/api/admin/projects/{pid}/api-keys", projectA.getId())
                        .header("Authorization", "Bearer " + director.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dir-key-a\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String dirKeyASecret = mapper.readTree(dirKeyARes).path("secret").asText();
        UUID dirKeyAId = UUID.fromString(mapper.readTree(dirKeyARes).path("id").asText());

        String dirKeyBRes = mvc.perform(post("/api/admin/projects/{pid}/api-keys", projectB.getId())
                        .header("Authorization", "Bearer " + director.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dir-key-b\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID dirKeyBId = UUID.fromString(mapper.readTree(dirKeyBRes).path("id").asText());

        String pendingInvRes = mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + director.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"projectId\":\"" + projectA.getId() + "\",\"canIssueApiKeys\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID pendingInvId = UUID.fromString(mapper.readTree(pendingInvRes).path("id").asText());
        String pendingInvCode = mapper.readTree(pendingInvRes).path("code").asText();

        // Invite & enroll general member with canIssueApiKeys = true on Project A
        String memInvRes = mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", org.getId())
                        .header("Authorization", "Bearer " + director.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"projectId\":\"" + projectA.getId() + "\",\"canIssueApiKeys\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        EnrolledAccount member = acceptAndEnroll("mem-rev@gov.test", mapper.readTree(memInvRes).path("code").asText());

        String memKeyRes = mvc.perform(post("/api/portal/projects/{pid}/api-keys", projectA.getId())
                        .header("Authorization", "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mem-key-a\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String memKeySecret = mapper.readTree(memKeyRes).path("secret").asText();
        UUID memKeyId = UUID.fromString(mapper.readTree(memKeyRes).path("id").asText());

        // Director cannot revoke a manager via /members
        mvc.perform(delete("/api/admin/projects/{pid}/members/{uid}", projectA.getId(), director.userId())
                        .header("Authorization", "Bearer " + director.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGER_REVOCATION_REQUIRES_ADMIN"));

        // Director revokes general member -> cascades membership deletion, scope retention, key revocation, refresh revocation
        mvc.perform(delete("/api/admin/projects/{pid}/members/{uid}", projectA.getId(), member.userId())
                        .header("Authorization", "Bearer " + director.accessToken()))
                .andExpect(status().isNoContent());

        assertThat(projectMemberships.findById(new ProjectMembershipId(projectA.getId(), member.userId()))).isEmpty();
        assertThat(users.findById(member.userId()).orElseThrow().isProjectScopeEnforced()).isTrue();
        assertThat(apiKeys.findById(memKeyId).orElseThrow().getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + memKeySecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"" + svc.getServiceKey() + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(member.refreshCookie()))
                .andExpect(status().isUnauthorized());
        String memberReLogin = loginWithTotp("mem-rev@gov.test", member.secret());
        mvc.perform(get("/api/admin/projects/{pid}/api-keys", projectA.getId())
                        .header("Authorization", "Bearer " + memberReLogin))
                .andExpect(status().isForbidden());

        // Platform admin lists managers, then revokes director from Project A
        mvc.perform(get("/api/admin/platform/projects/{pid}/managers", projectA.getId())
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(director.userId().toString()))
                .andExpect(jsonPath("$[0].activeApiKeyCount").value(1));

        mvc.perform(delete("/api/admin/platform/projects/{pid}/managers/{uid}", projectA.getId(), director.userId())
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isNoContent());

        assertThat(projectMemberships.findById(new ProjectMembershipId(projectA.getId(), director.userId()))).isEmpty();
        assertThat(projectMemberships.findById(new ProjectMembershipId(projectB.getId(), director.userId()))).isPresent();
        assertThat(users.findById(director.userId()).orElseThrow().isProjectScopeEnforced()).isTrue();
        assertThat(apiKeys.findById(dirKeyAId).orElseThrow().getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(apiKeys.findById(dirKeyBId).orElseThrow().getStatus()).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(accountInvitations.findById(pendingInvId).orElseThrow().getRevokedAt()).isNotNull();

        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + dirKeyASecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"" + svc.getServiceKey() + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + pendingInvCode + "\",\"email\":\"newcomer@gov.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/refresh").cookie(director.refreshCookie()))
                .andExpect(status().isUnauthorized());

        String directorReLogin = loginWithTotp("dir-rev@gov.test", director.secret());
        mvc.perform(get("/api/admin/projects/{pid}/api-keys", projectA.getId())
                        .header("Authorization", "Bearer " + directorReLogin))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/projects/{pid}/api-keys", projectB.getId())
                        .header("Authorization", "Bearer " + directorReLogin))
                .andExpect(status().isOk());
    }

    @Test
    void offboardingDisablesUserAndRevokesAllActiveApiKeys() throws Exception {
        Organization org = organizations.save(new Organization("Offboard Org " + UUID.randomUUID()));
        Project projectA = projects.save(new Project(org.getId(), "Offboard A"));
        Project projectB = projects.save(new Project(org.getId(), "Offboard B"));
        LlmService svc = services.save(new LlmService(org.getId(), "off-svc-" + UUID.randomUUID().toString().substring(0, 6),
                "Off Service", FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        serviceAccess.save(new ProjectServiceAccess(projectA.getId(), svc.getId()));

        AppUser user = createDirector(org, projectA, "offboard-user@gov.test");
        projectMemberships.save(new ProjectMembership(projectB.getId(), user.getId(), ProjectMembership.Role.MANAGER, true));
        String token = sessionToken(user);

        String keyARes = mvc.perform(post("/api/admin/projects/{pid}/api-keys", projectA.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"off-key-a\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String keyASecret = mapper.readTree(keyARes).path("secret").asText();
        UUID keyAId = UUID.fromString(mapper.readTree(keyARes).path("id").asText());

        String keyBRes = mvc.perform(post("/api/admin/projects/{pid}/api-keys", projectB.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"off-key-b\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID keyBId = UUID.fromString(mapper.readTree(keyBRes).path("id").asText());

        mvc.perform(patch("/api/admin/platform/users/{uid}", user.getId())
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(apiKeys.findById(keyAId).orElseThrow().getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(apiKeys.findById(keyBId).orElseThrow().getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + keyASecret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"" + svc.getServiceKey() + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/portal/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invitationAcceptRevalidatesProjectAndIssuerAndHandlesConcurrency() throws Exception {
        Organization org = organizations.save(new Organization("Invite Reval Org " + UUID.randomUUID()));
        Project projectA = projects.save(new Project(org.getId(), "Suspended Target"));
        Project projectB = projects.save(new Project(org.getId(), "Demoted Issuer Target"));
        Project projectC = projects.save(new Project(org.getId(), "Concurrent Target"));

        AppUser director = createDirector(org, projectA, "dir-inv@gov.test");
        projectMemberships.save(new ProjectMembership(projectB.getId(), director.getId(), ProjectMembership.Role.MANAGER, true));
        projectMemberships.save(new ProjectMembership(projectC.getId(), director.getId(), ProjectMembership.Role.MANAGER, true));
        String directorToken = sessionToken(director);

        String invA = issueInvite(org.getId(), projectA.getId(), directorToken);
        String invB = issueInvite(org.getId(), projectB.getId(), directorToken);
        String invC = issueInvite(org.getId(), projectC.getId(), directorToken);

        // 1. Project suspended before accept -> 409 PROJECT_NOT_ACTIVE
        mvc.perform(patch("/api/admin/projects/{pid}/status", projectA.getId())
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"revokeActiveApiKeys\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + invA + "\",\"email\":\"susp@gov.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_ACTIVE"));

        // 2. Issuer loses manager role before accept -> 409 INVITATION_ISSUER_REVOKED
        projectMemberships.deleteById(new ProjectMembershipId(projectB.getId(), director.getId()));
        projectMemberships.flush();
        mvc.perform(post("/api/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + invB + "\",\"email\":\"demoted@gov.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_ISSUER_REVOKED"));

        // 3. Concurrent accept race on single valid invitation -> strictly 1 succeeds
        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final String email = "race-acc-" + i + "@gov.test";
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mvc.perform(post("/api/auth/invitations/accept")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"code\":\"" + invC + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
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
    }

    @Test
    void concurrentHaDecisionsForConnectionRequestsAndRuntimeApprovals() throws Exception {
        Organization org = organizations.save(new Organization("Race Org " + UUID.randomUUID()));
        Project project = projects.save(new Project(org.getId(), "Race Project"));
        AppUser director = createDirector(org, project, "dir-race@gov.test");
        String directorToken = sessionToken(director);

        long endpointsBefore = endpoints.count();
        String submittedJson = mvc.perform(post("/api/portal/projects/{pid}/connection-requests", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"RUNTIME\",\"displayName\":\"Race GPU\",\"baseUrl\":\"http://100.106.74.55:4040\",\"runtimeType\":\"LLAMA_CPP\",\"reason\":\"Need GPU\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String reqId = mapper.readTree(submittedJson).path("id").asText();

        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mvc.perform(post("/api/admin/platform/connection-requests/{id}/decision", reqId)
                                    .header("X-Admin-Token", ADMIN_TOKEN)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"approved\":true,\"reason\":\"approve race\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(endpoints.count()).isEqualTo(endpointsBefore + 1);

        // Concurrent ProjectRuntimeApproval approve vs revoke race
        InferenceNode node = nodes.save(new InferenceNode(org.getId(), "race-node", null, "DIRECT", null));
        RuntimeEndpoint ep = endpoints.saveAndFlush(new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO,
                "http://127.0.0.1:49999", null));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        mvc.perform(put("/api/admin/platform/runtime-endpoints/{id}/trust", ep.getId())
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classification\":\"INTERNAL\",\"evidence\":\"internal check\"}"))
                .andExpect(status().isOk());

        var approvalExecutor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Integer> approveFuture = approvalExecutor.submit(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(put("/api/admin/platform/projects/{pid}/runtime-approvals/{eid}", project.getId(), ep.getId())
                                .header("X-Admin-Token", ADMIN_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"manualAllowed\":true,\"failoverAllowed\":false,\"expiresAt\":\"" + now.plusSeconds(600) + "\"}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> revokeFuture = approvalExecutor.submit(() -> {
                ready.countDown();
                go.await();
                return mvc.perform(delete("/api/admin/platform/projects/{pid}/runtime-approvals/{eid}", project.getId(), ep.getId())
                                .header("X-Admin-Token", ADMIN_TOKEN))
                        .andReturn().getResponse().getStatus();
            });
            ready.await();
            go.countDown();
            assertThat(approveFuture.get()).isEqualTo(204);
            assertThat(revokeFuture.get()).isEqualTo(204);
        } finally {
            approvalExecutor.shutdownNow();
        }
        String approvalView = mvc.perform(get("/api/admin/platform/projects/{pid}/runtime-approvals/{eid}", project.getId(), ep.getId())
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode approvalNode = mapper.readTree(approvalView);
        assertThat(approvalNode.path("valid").asBoolean()).isEqualTo(approvalNode.path("manualAllowed").asBoolean());
    }

    @Test
    void runtimeTrustExternalNoRetentionMetadataRequiredAndExplicitRevoke() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var servers = new MockServers()) {
            HttpServer server = servers.start(calls);
            Organization org = organizations.save(new Organization("Trust Meta Org " + UUID.randomUUID()));
            Project project = projects.save(new Project(org.getId(), "Trust Project"));
            InferenceNode node = nodes.save(new InferenceNode(org.getId(), "trust-node", null, "DIRECT", null));
            RuntimeEndpoint ep = saveEndpoint(node, "ext-gpu", server);
            ModelDeployment model = deployments.save(new ModelDeployment(ep.getId(), "ext-gpu-model", "compat",
                    "Ext GPU Model", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));
            LlmService svc = services.save(new LlmService(org.getId(), "trust-svc-" + UUID.randomUUID().toString().substring(0, 6),
                    "Trust Svc", FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            targets.save(new ServiceTarget(svc.getId(), model.getId(), 1, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), svc.getId()));
            String apiKey = apiKeyService.issue(project.getId(), "trust-key", null).secret();

            Instant future = Instant.now().plusSeconds(1200).truncatedTo(ChronoUnit.SECONDS);

            // Missing metadata -> 400 EXTERNAL_NO_RETENTION_METADATA_REQUIRED
            mvc.perform(put("/api/admin/platform/runtime-endpoints/{id}/trust", ep.getId())
                            .header("X-Admin-Token", ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"classification\":\"EXTERNAL_NO_RETENTION\",\"evidence\":\"DPA\",\"expiresAt\":\"" + future + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EXTERNAL_NO_RETENTION_METADATA_REQUIRED"));

            // With full metadata -> 200 & metadata returned
            mvc.perform(put("/api/admin/platform/runtime-endpoints/{id}/trust", ep.getId())
                            .header("X-Admin-Token", ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"classification\":\"EXTERNAL_NO_RETENTION\",\"evidence\":\"Signed DPA #42\",\"expiresAt\":\"" + future
                                    + "\",\"vendorName\":\"Partner GPU Inc\",\"processingLocation\":\"Seoul, KR\",\"retentionPolicy\":\"ZERO_DATA_RETENTION\",\"approvalScope\":\"Project inference\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.vendorName").value("Partner GPU Inc"))
                    .andExpect(jsonPath("$.processingLocation").value("Seoul, KR"))
                    .andExpect(jsonPath("$.retentionPolicy").value("ZERO_DATA_RETENTION"))
                    .andExpect(jsonPath("$.approvalScope").value("Project inference"));

            mvc.perform(put("/api/admin/platform/projects/{pid}/runtime-approvals/{eid}", project.getId(), ep.getId())
                            .header("X-Admin-Token", ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"manualAllowed\":true,\"failoverAllowed\":false,\"expiresAt\":\"" + future.minusSeconds(60) + "\"}"))
                    .andExpect(status().isNoContent());

            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"" + svc.getServiceKey() + "\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                    .andExpect(status().isOk());
            assertThat(calls).hasValue(1);

            // Explicit revoke -> immediately invalidates and blocks routing
            mvc.perform(post("/api/admin/platform/runtime-endpoints/{id}/trust/revoke", ep.getId())
                            .header("X-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.revokedAt").isNotEmpty());

            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"" + svc.getServiceKey() + "\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"))
                    .andExpect(status().isServiceUnavailable());
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void playgroundRejectsNonAdminsAndNonInternalRuntimesOnCleanPrompts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var servers = new MockServers()) {
            HttpServer server = servers.start(calls);
            Organization org = organizations.save(new Organization("Playground Org " + UUID.randomUUID()));
            Project project = projects.save(new Project(org.getId(), "PG Project"));
            AppUser director = createDirector(org, project, "dir-pg@gov.test");
            String directorToken = sessionToken(director);
            AppUser general = identity.createUser("gen-pg@gov.test", PASSWORD, false, AccountTier.GENERAL_USER, director.getId());
            general.enforceProjectScope();
            users.save(general);
            identity.grantMembership(org.getId(), general.getId(), OrganizationRole.DEVELOPER);
            projectMemberships.save(new ProjectMembership(project.getId(), general.getId(), ProjectMembership.Role.MEMBER, false));
            String generalToken = sessionToken(general);

            InferenceNode node = nodes.save(new InferenceNode(org.getId(), "pg-node", null, "DIRECT", null));
            RuntimeEndpoint unclassifiedEp = saveEndpoint(node, "unclassified", server);
            RuntimeEndpoint extNoRetEp = saveEndpoint(node, "ext-no-ret", server);
            RuntimeEndpoint extUnverifiedEp = saveEndpoint(node, "ext-unver", server);
            RuntimeEndpoint revokedEp = saveEndpoint(node, "revoked", server);
            RuntimeEndpoint internalEp = saveEndpoint(node, "internal", server);

            ModelDeployment unclassifiedModel = deployments.save(new ModelDeployment(unclassifiedEp.getId(), "m1", "m1", "M1", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));
            ModelDeployment extNoRetModel = deployments.save(new ModelDeployment(extNoRetEp.getId(), "m2", "m2", "M2", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));
            ModelDeployment extUnverifiedModel = deployments.save(new ModelDeployment(extUnverifiedEp.getId(), "m3", "m3", "M3", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));
            ModelDeployment revokedModel = deployments.save(new ModelDeployment(revokedEp.getId(), "m4", "m4", "M4", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));
            ModelDeployment internalModel = deployments.save(new ModelDeployment(internalEp.getId(), "m5", "m5", "M5", null, null, 8192, true, 4, "[\"CHAT_COMPLETION\"]"));

            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            runtimeTrust.saveAndFlush(new RuntimeTrustAttestation(extNoRetEp.getId(),
                    RuntimeTrustAttestation.Classification.EXTERNAL_NO_RETENTION, extNoRetEp.getBaseUrl(),
                    "DPA", now.plusSeconds(600), null, now, "Vendor", "KR", "ZDR", "Scope"));
            runtimeTrust.saveAndFlush(new RuntimeTrustAttestation(extUnverifiedEp.getId(),
                    RuntimeTrustAttestation.Classification.EXTERNAL_UNVERIFIED, extUnverifiedEp.getBaseUrl(),
                    "Unverified", null, null, now));
            var revokedTrust = new RuntimeTrustAttestation(revokedEp.getId(),
                    RuntimeTrustAttestation.Classification.INTERNAL, revokedEp.getBaseUrl(),
                    "Internal", null, null, now);
            revokedTrust.revoke(now);
            runtimeTrust.saveAndFlush(revokedTrust);
            runtimeTrust.saveAndFlush(new RuntimeTrustAttestation(internalEp.getId(),
                    RuntimeTrustAttestation.Classification.INTERNAL, internalEp.getBaseUrl(),
                    "Internal", null, null, now));

            String cleanPayload = "{\"targetId\":\"" + internalModel.getId() + "\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";

            // Director & General User -> 403
            mvc.perform(post("/api/admin/organizations/{orgId}/playground/chat", org.getId())
                            .header("Authorization", "Bearer " + directorToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cleanPayload))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/organizations/{orgId}/playground/chat", org.getId())
                            .header("Authorization", "Bearer " + generalToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cleanPayload))
                    .andExpect(status().isForbidden());

            // Admin against non-internal runtimes on clean prompt -> 403
            for (ModelDeployment blocked : List.of(unclassifiedModel, extNoRetModel, extUnverifiedModel, revokedModel)) {
                String body = "{\"targetId\":\"" + blocked.getId() + "\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}";
                mvc.perform(post("/api/admin/organizations/{orgId}/playground/chat", org.getId())
                                .header("X-Admin-Token", ADMIN_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andExpect(status().isForbidden());
            }
            assertThat(calls).hasValue(0);

            // Admin against INTERNAL runtime -> 200
            mvc.perform(post("/api/admin/organizations/{orgId}/playground/chat", org.getId())
                            .header("X-Admin-Token", ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(cleanPayload))
                    .andExpect(status().isOk());
            assertThat(calls).hasValue(1);
        }
    }

    private RuntimeEndpoint saveEndpoint(InferenceNode node, String name, HttpServer server) {
        RuntimeEndpoint ep = new RuntimeEndpoint(node.getId(), name, RuntimeType.LM_STUDIO,
                "http://127.0.0.1:" + server.getAddress().getPort(), null,
                BigDecimal.ONE, BigDecimal.ONE, Currency.KRW);
        ep.recordHealth(true);
        return endpoints.saveAndFlush(ep);
    }

    private void classifyAndApproveInternal(UUID projectId, UUID endpointId) throws Exception {
        Instant future = Instant.now().plusSeconds(1200).truncatedTo(ChronoUnit.SECONDS);
        mvc.perform(put("/api/admin/platform/runtime-endpoints/{id}/trust", endpointId)
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classification\":\"INTERNAL\",\"evidence\":\"internal verified\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/platform/projects/{pid}/runtime-approvals/{eid}", projectId, endpointId)
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manualAllowed\":true,\"failoverAllowed\":false,\"expiresAt\":\"" + future + "\"}"))
                .andExpect(status().isNoContent());
    }

    private void classifyAndApproveNoRetention(UUID projectId, UUID endpointId) throws Exception {
        Instant future = Instant.now().plusSeconds(1200).truncatedTo(ChronoUnit.SECONDS);
        mvc.perform(put("/api/admin/platform/runtime-endpoints/{id}/trust", endpointId)
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classification\":\"EXTERNAL_NO_RETENTION\",\"evidence\":\"DPA\",\"expiresAt\":\"" + future
                                + "\",\"vendorName\":\"External GPU\",\"processingLocation\":\"Seoul, KR\",\"retentionPolicy\":\"ZERO_RETENTION\",\"approvalScope\":\"Approved project\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/platform/projects/{pid}/runtime-approvals/{eid}", projectId, endpointId)
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manualAllowed\":true,\"failoverAllowed\":true,\"expiresAt\":\"" + future.minusSeconds(60) + "\"}"))
                .andExpect(status().isNoContent());
    }

    private AppUser createDirector(Organization org, Project project, String email) {
        AppUser user = identity.createUser(email, PASSWORD, false, AccountTier.PROJECT_DIRECTOR, null);
        user.enforceProjectScope();
        user = users.save(user);
        identity.grantMembership(org.getId(), user.getId(), OrganizationRole.DEVELOPER);
        projectMemberships.save(new ProjectMembership(project.getId(), user.getId(), ProjectMembership.Role.MANAGER, true));
        return user;
    }

    private String sessionToken(AppUser user) {
        if (!user.isTotpEnabled()) {
            user.assignPendingTotpSecret("encrypted-test-secret");
            user.activateTotp(1L, Instant.now());
            user = users.save(user);
        }
        AuthRefreshToken token = refreshTokens.save(new AuthRefreshToken(
                user.getId(), UUID.randomUUID().toString(), Instant.now().plusSeconds(3600), true));
        return accessTokens.issue(user, token.getId(), true);
    }

    private String issueInvite(UUID orgId, UUID projectId, String directorToken) throws Exception {
        String res = mvc.perform(post("/api/admin/organizations/{orgId}/account-invitations", orgId)
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTier\":\"GENERAL_USER\",\"projectId\":\"" + projectId + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).path("code").asText();
    }

    private EnrolledAccount acceptAndEnroll(String email, String code) throws Exception {
        MvcResult acceptRes = mvc.perform(post("/api/auth/invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode acceptJson = mapper.readTree(acceptRes.getResponse().getContentAsString());
        UUID userId = UUID.fromString(acceptJson.path("user").path("id").asText());
        String preMfa = acceptJson.path("accessToken").asText();

        String setupJson = mvc.perform(post("/api/auth/totp/setup").header("Authorization", "Bearer " + preMfa))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = mapper.readTree(setupJson).path("secret").asText();
        String totpCode = totpService.generateCode(secret, Instant.now());

        MvcResult enrollRes = mvc.perform(post("/api/auth/totp/enroll")
                        .header("Authorization", "Bearer " + preMfa)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totpCode + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode enrollJson = mapper.readTree(enrollRes.getResponse().getContentAsString());
        Cookie refreshCookie = enrollRes.getResponse().getCookie("aiconnect_refresh");
        return new EnrolledAccount(userId, enrollJson.path("session").path("accessToken").asText(), secret, refreshCookie);
    }

    private String loginWithTotp(String email, String secret) throws Exception {
        String loginRes = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String pendingToken = mapper.readTree(loginRes).path("accessToken").asText();
        String code = totpService.generateCode(secret, Instant.now().plusSeconds(30));
        String verifyRes = mvc.perform(post("/api/auth/totp/verify")
                        .header("Authorization", "Bearer " + pendingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(verifyRes).path("accessToken").asText();
    }

    private record EnrolledAccount(UUID userId, String accessToken, String secret, Cookie refreshCookie) { }

    private static final class MockServers implements AutoCloseable {
        private final List<HttpServer> started = new ArrayList<>();

        HttpServer start(AtomicInteger calls) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = "{\"model\":\"physical\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            started.add(server);
            return server;
        }

        @Override
        public void close() {
            started.forEach(s -> s.stop(0));
        }
    }
}
