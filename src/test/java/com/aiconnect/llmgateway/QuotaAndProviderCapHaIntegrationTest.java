package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.admin.AdminDtos;
import com.aiconnect.llmgateway.admin.ControlPlaneService;
import com.aiconnect.llmgateway.diagnostic.RequestDiagnosticService;
import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.external.ProjectExternalAccessReservation;
import com.aiconnect.llmgateway.external.ProjectExternalAccessReservationRepository;
import com.aiconnect.llmgateway.external.ProjectExternalAccessService;
import com.aiconnect.llmgateway.external.ProviderCapReservationService;
import com.aiconnect.llmgateway.gateway.StreamingChatCompletionGateway;
import com.aiconnect.llmgateway.gateway.StreamingGatewayResult;
import com.aiconnect.llmgateway.identity.*;
import com.aiconnect.llmgateway.quota.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.security.ProjectRuntimeApprovalRepository;
import com.aiconnect.llmgateway.security.RuntimeTrustAttestationRepository;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
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

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:aiconnect_quota_ha;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "aiconnect.security.external-url.allow-loopback-test-server=true"
})
class QuotaAndProviderCapHaIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyService apiKeys;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired RuntimeTrustAttestationRepository runtimeTrust;
    @Autowired ProjectRuntimeApprovalRepository runtimeApprovals;
    @Autowired ExternalProviderRepository providers;
    @Autowired ModelDeploymentRepository deployments;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired ProjectServiceAccessRepository serviceAccess;
    @Autowired ProjectExternalAccessRepository externalAccess;
    @Autowired ProjectExternalAccessService externalAccessService;
    @Autowired ProjectExternalAccessReservationRepository externalReservations;
    @Autowired ProviderCapReservationService providerCapService;
    @Autowired SpendQuotaRepository spendQuotas;
    @Autowired SpendQuotaReservationRepository spendReservations;
    @Autowired SpendQuotaService spendQuotaService;
    @Autowired ProjectQuotaRepository projectQuotas;
    @Autowired LlmRequestRepository requests;
    @Autowired RequestDiagnosticService diagnostics;
    @Autowired AppUserRepository users;
    @Autowired OrganizationMemberRepository organizationMembers;
    @Autowired ProjectMembershipRepository projectMemberships;
    @Autowired AuthRefreshTokenRepository refreshTokens;
    @Autowired AccessTokenService accessTokenService;
    @Autowired SecretCipher cipher;
    @Autowired ControlPlaneService controlPlane;
    @Autowired StreamingChatCompletionGateway streamingGateway;

    // Scenario 1: currency-required (400), legacy null-currency fail-closed, currency mismatch (409),
    // output limit required (400), and missing pricing (409).
    @Test
    void providerCapValidationContractsAndLegacyNullCurrencyFailClosed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = okServer(calls, null);
        try {
            Organization org = organizations.save(new Organization("Cap Validation Org"));
            Project project = projects.save(new Project(org.getId(), "cap-validation"));
            IssuedApiKey key = apiKeys.issue(project.getId(), "k1", null);
            ExternalProvider provider = healthyProvider(org, "OpenAI Cap", server);

            // 1a. API decide requires currency when monthlyCostLimit != null -> 400 PROVIDER_CAP_CURRENCY_REQUIRED
            mvc.perform(patch("/api/admin/projects/{projectId}/external-access/{providerId}", project.getId(), provider.getId())
                            .header("X-Admin-Token", "integration-admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"APPROVED\",\"manualAllowed\":true,\"autoFailoverEnabled\":false,\"monthlyCostLimit\":100}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PROVIDER_CAP_CURRENCY_REQUIRED"));

            ModelDeployment pricedKrw = deployments.save(ModelDeployment.external(
                    provider.getId(), "gpt-krw", "gpt-krw", "GPT KRW", 8192, 10,
                    "[\"CHAT_COMPLETION\"]", BigDecimal.valueOf(1000), BigDecimal.valueOf(10000), Currency.KRW));
            LlmService svc = services.save(new LlmService(org.getId(), "cap-svc", "Cap Service",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            controlPlane.addTarget(svc.getId(), new AdminDtos.CreateTarget(pricedKrw.getId(), 1, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), svc.getId()));

            // 1b. Legacy null-currency cap row must fail closed with EXTERNAL_PROVIDER_CAP_RESERVATION_UNAVAILABLE
            ProjectExternalAccess legacy = new ProjectExternalAccess(project.getId(), provider.getId(), null, "legacy");
            legacy.decide(ExternalAccessStatus.APPROVED, true, true, BigDecimal.valueOf(100), null, null);
            externalAccess.saveAndFlush(legacy);

            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"cap-svc\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .andExpect(status().isServiceUnavailable());
            assertThat(calls).hasValue(0);
            LlmRequest legacyTrace = requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0);
            assertThat(diagnostics.view(legacyTrace.getId()).orElseThrow().targets().get(0).reasonCodes())
                    .contains("EXTERNAL_PROVIDER_CAP_RESERVATION_UNAVAILABLE");

            // 1c. Currency mismatch: cap in USD, deployment priced in KRW -> 409 PROVIDER_CAP_CURRENCY_MISMATCH
            mvc.perform(patch("/api/admin/projects/{projectId}/external-access/{providerId}", project.getId(), provider.getId())
                            .header("X-Admin-Token", "integration-admin-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"APPROVED\",\"manualAllowed\":true,\"autoFailoverEnabled\":false,\"monthlyCostLimit\":100,\"monthlyCostCurrency\":\"USD\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.monthlyCostCurrency").value("USD"));

            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"cap-svc\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("PROVIDER_CAP_CURRENCY_MISMATCH"));
            assertThat(calls).hasValue(0);

            // 1d. Align currency to KRW, omit max_tokens -> 400 QUOTA_OUTPUT_LIMIT_REQUIRED
            externalAccessService.decide(project.getId(), provider.getId(), ExternalAccessStatus.APPROVED,
                    true, false, BigDecimal.valueOf(100), Currency.KRW, null, null);
            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"cap-svc\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("QUOTA_OUTPUT_LIMIT_REQUIRED"));
            assertThat(calls).hasValue(0);

            // 1e. Missing pricing on external model -> 409 QUOTA_PRICE_UNAVAILABLE
            ModelDeployment unpriced = deployments.save(ModelDeployment.external(
                    provider.getId(), "gpt-unpriced", "gpt-unpriced", "Unpriced", 8192, 10,
                    "[\"CHAT_COMPLETION\"]", null, null, Currency.KRW));
            LlmService unpricedSvc = services.save(new LlmService(org.getId(), "unpriced-svc", "Unpriced Svc",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            controlPlane.addTarget(unpricedSvc.getId(), new AdminDtos.CreateTarget(unpriced.getId(), 1, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), unpricedSvc.getId()));

            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"unpriced-svc\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("QUOTA_PRICE_UNAVAILABLE"));
            assertThat(calls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    // Scenario 2: Barrier-synchronized concurrent reservation contention for JSON and streaming (zero overshoot)
    @Test
    void concurrentBarrierEnforcesProviderCapAndSpendQuotaWithoutOvershootInJsonAndStreaming() throws Exception {
        // 2a. JSON concurrent provider cap barrier (cap=2.00 KRW; worst-case per request = 1.00 KRW -> strictly 2 succeed)
        runBarrierTest(false, false);
        // 2b. Streaming concurrent provider cap barrier (strictly 2 succeed)
        runBarrierTest(true, false);
        // 2c. JSON concurrent SpendQuota barrier (strictly 2 succeed)
        runBarrierTest(false, true);
    }

    private void runBarrierTest(boolean stream, boolean withSpendQuota) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            byte[] response = stream
                    ? ("data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)
                    : ("{\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Organization org = organizations.save(new Organization("Barrier Org " + stream + "-" + withSpendQuota + "-" + UUID.randomUUID()));
            Project project = projects.save(new Project(org.getId(), "barrier-proj"));
            IssuedApiKey key = apiKeys.issue(project.getId(), "barrier-key", null);
            ExternalProvider provider = healthyProvider(org, "Barrier Provider", server);
            // Input price 0, output price 10_000 KRW/1M -> max_tokens=100 => worst-case 1.000000 KRW per request
            ModelDeployment model = deployments.save(ModelDeployment.external(
                    provider.getId(), "barrier-model", "barrier-model", "Barrier Model", 8192, 20,
                    "[\"CHAT_COMPLETION\",\"STREAMING\"]", BigDecimal.ZERO, BigDecimal.valueOf(10_000), Currency.KRW));
            LlmService svc = services.save(new LlmService(org.getId(), "barrier-svc", "Barrier Svc",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            controlPlane.addTarget(svc.getId(), new AdminDtos.CreateTarget(model.getId(), 1, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), svc.getId()));

            if (withSpendQuota) {
                externalAccessService.decide(project.getId(), provider.getId(), ExternalAccessStatus.APPROVED,
                        true, false, null, null, null, null);
                spendQuotas.save(new SpendQuota(org.getId(), SpendQuotaScope.PROJECT, project.getId(),
                        "proj-cap", Currency.KRW, BigDecimal.valueOf(2), SpendQuotaPeriod.MONTHLY, true, true));
            } else {
                externalAccessService.decide(project.getId(), provider.getId(), ExternalAccessStatus.APPROVED,
                        true, false, BigDecimal.valueOf(2), Currency.KRW, null, null);
            }

            int threads = 6;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<Integer>> futures = new ArrayList<>();
            String body = "{\"model\":\"barrier-svc\",\"stream\":" + stream
                    + ",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    MvcResult res = mvc.perform(post("/v1/chat/completions")
                                    .header("Authorization", "Bearer " + key.secret())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                    return res.getResponse().getStatus();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int okCount = 0;
            int rejectedCount = 0;
            for (Future<Integer> future : futures) {
                int status = future.get(15, TimeUnit.SECONDS);
                if (status == 200) okCount++;
                else if (status == 429) rejectedCount++;
                else throw new AssertionError("Unexpected HTTP status during barrier test: " + status);
            }
            pool.shutdownNow();

            assertThat(okCount).isEqualTo(2);
            assertThat(rejectedCount).isEqualTo(4);
            assertThat(calls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    // Scenario 3: Provider A fails pre-send (503) -> Provider A reservation immediately RELEASED, Provider B reserved & settled
    @Test
    void failoverImmediatelyReleasesFailedProviderReservationAndPreservesItsBudget() throws Exception {
        AtomicInteger callsA = new AtomicInteger();
        AtomicInteger callsB = new AtomicInteger();
        AtomicInteger statusA = new AtomicInteger(503);
        HttpServer serverA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverA.createContext("/v1/chat/completions", exchange -> {
            callsA.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            int s = statusA.get();
            byte[] body = (s == 503
                    ? "{\"error\":{\"message\":\"temporarily unavailable\"}}"
                    : "{\"id\":\"ok-a\",\"model\":\"m-a\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"from A\"}}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(s, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HttpServer serverB = okServer(callsB, null);
        serverA.start();
        try {
            Organization org = organizations.save(new Organization("Failover Release Org"));
            Project project = projects.save(new Project(org.getId(), "failover-proj"));
            IssuedApiKey key = apiKeys.issue(project.getId(), "fk", null);
            ExternalProvider providerA = healthyProvider(org, "Provider A", serverA);
            ExternalProvider providerB = healthyProvider(org, "Provider B", serverB);
            ModelDeployment modelA = deployments.save(ModelDeployment.external(
                    providerA.getId(), "m-a", "compat", "Model A", 8192, 10,
                    "[\"CHAT_COMPLETION\"]", BigDecimal.ZERO, BigDecimal.valueOf(10_000), Currency.KRW));
            ModelDeployment modelB = deployments.save(ModelDeployment.external(
                    providerB.getId(), "m-b", "compat", "Model B", 8192, 10,
                    "[\"CHAT_COMPLETION\"]", BigDecimal.ZERO, BigDecimal.valueOf(10_000), Currency.KRW));
            LlmService svc = services.save(new LlmService(org.getId(), "failover-cap", "Failover Cap",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.AGGRESSIVE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            controlPlane.addTarget(svc.getId(), new AdminDtos.CreateTarget(modelA.getId(), 1, 100, false, null));
            controlPlane.addTarget(svc.getId(), new AdminDtos.CreateTarget(modelB.getId(), 2, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), svc.getId()));

            // Each provider has cap = 1.00 KRW (strictly 1 request worth at max_tokens=100)
            externalAccessService.decide(project.getId(), providerA.getId(), ExternalAccessStatus.APPROVED,
                    true, true, BigDecimal.ONE, Currency.KRW, null, null);
            externalAccessService.decide(project.getId(), providerB.getId(), ExternalAccessStatus.APPROVED,
                    true, true, BigDecimal.ONE, Currency.KRW, null, null);

            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"failover-cap\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .andExpect(status().isOk());

            assertThat(callsA).hasValue(1);
            assertThat(callsB).hasValue(1);

            List<ProjectExternalAccessReservation> resA = externalReservations
                    .findByProjectIdAndProviderIdOrderByCreatedAtDesc(project.getId(), providerA.getId());
            List<ProjectExternalAccessReservation> resB = externalReservations
                    .findByProjectIdAndProviderIdOrderByCreatedAtDesc(project.getId(), providerB.getId());
            assertThat(resA).hasSize(1);
            assertThat(resA.get(0).getStatus()).isEqualTo(ProjectExternalAccessReservation.ReservationStatus.RELEASED);
            assertThat(resB).hasSize(1);
            assertThat(resB.get(0).getStatus()).isEqualTo(ProjectExternalAccessReservation.ReservationStatus.SETTLED);

            // Provider A recovers -> still has its full 1.00 KRW budget available for another request!
            providerA.recordHealth(true);
            providers.save(providerA);
            statusA.set(200);
            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"failover-cap\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .andExpect(status().isOk());
            assertThat(callsA).hasValue(2);
        } finally {
            serverA.stop(0);
            serverB.stop(0);
        }
    }

    // Scenario 4: Mid-stream client cancel / stream interruption without upstream usage -> HELD_UNSETTLED
    @Test
    void midStreamInterruptionWithoutUsageTransitionsReservationsToHeldUnsettledAndBlocksFollowUp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer streamServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        streamServer.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] firstChunk = "data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            // Advertise larger Content-Length and close early -> triggers IOException on read without usage chunk
            exchange.sendResponseHeaders(200, 4096);
            exchange.getResponseBody().write(firstChunk);
            exchange.getResponseBody().flush();
            try { Thread.sleep(25); } catch (InterruptedException ignored) { }
            exchange.close();
        });
        streamServer.start();
        try {
            Organization org = organizations.save(new Organization("Stream Abort Org"));
            Project project = projects.save(new Project(org.getId(), "stream-abort"));
            IssuedApiKey key = apiKeys.issue(project.getId(), "sk", null);
            ExternalProvider provider = healthyProvider(org, "Stream Abort Provider", streamServer);
            ModelDeployment model = deployments.save(ModelDeployment.external(
                    provider.getId(), "stream-abort-m", "stream-abort-m", "Stream Abort", 8192, 10,
                    "[\"CHAT_COMPLETION\",\"STREAMING\"]", BigDecimal.ZERO, BigDecimal.valueOf(10_000), Currency.KRW));
            LlmService svc = services.save(new LlmService(org.getId(), "stream-abort-svc", "Stream Abort Svc",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            controlPlane.addTarget(svc.getId(), new AdminDtos.CreateTarget(model.getId(), 1, 100, false, null));
            serviceAccess.save(new ProjectServiceAccess(project.getId(), svc.getId()));
            externalAccessService.decide(project.getId(), provider.getId(), ExternalAccessStatus.APPROVED,
                    true, false, BigDecimal.ONE, Currency.KRW, null, null);
            SpendQuota sq = spendQuotas.save(new SpendQuota(org.getId(), SpendQuotaScope.PROJECT, project.getId(),
                    "stream-sq", Currency.KRW, BigDecimal.ONE, SpendQuotaPeriod.MONTHLY, true, true));

            String body = "{\"model\":\"stream-abort-svc\",\"stream\":true,\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            assertThatThrownBy(() -> mvc.perform(post("/v1/chat/completions")
                    .header("Authorization", "Bearer " + key.secret())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)))
                    .isInstanceOf(Exception.class);

            assertThat(calls).hasValue(1);
            List<ProjectExternalAccessReservation> extRes = externalReservations
                    .findByProjectIdAndProviderIdOrderByCreatedAtDesc(project.getId(), provider.getId());
            assertThat(extRes).hasSize(1);
            assertThat(extRes.get(0).getStatus()).isEqualTo(ProjectExternalAccessReservation.ReservationStatus.HELD_UNSETTLED);
            assertThat(extRes.get(0).getReason()).isEqualTo("STREAM_INTERRUPTED_UNSETTLED");

            List<SpendQuotaReservation> sqRes = spendReservations.findByQuotaIdOrderByCreatedAtDesc(sq.getId());
            assertThat(sqRes).hasSize(1);
            assertThat(sqRes.get(0).getStatus()).isEqualTo(SpendQuotaReservation.ReservationStatus.HELD_UNSETTLED);
            assertThat(sqRes.get(0).getReason()).isEqualTo("STREAM_INTERRUPTED_UNSETTLED");

            LlmRequest audit = requests.findTop50ByProjectIdOrderByStartedAtDesc(project.getId()).get(0);
            assertThat(audit.getStatus()).isEqualTo(RequestStatus.FAILED);
            assertThat(audit.getErrorCode()).isEqualTo("STREAM_INTERRUPTED");

            // Subsequent request in same period must be blocked because HELD_UNSETTLED holds the budget
            mvc.perform(post("/v1/chat/completions")
                            .header("Authorization", "Bearer " + key.secret())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\":\"stream-abort-svc\",\"stream\":false,\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("QUOTA_EXCEEDED"));
            assertThat(calls).hasValue(1);

            // Also verify direct client-side early stream close triggers HELD_UNSETTLED
            Project project2 = projects.save(new Project(org.getId(), "client-close"));
            IssuedApiKey key2 = apiKeys.issue(project2.getId(), "sk2", null);
            serviceAccess.save(new ProjectServiceAccess(project2.getId(), svc.getId()));
            externalAccessService.decide(project2.getId(), provider.getId(), ExternalAccessStatus.APPROVED,
                    true, false, BigDecimal.ONE, Currency.KRW, null, null);
            StreamingGatewayResult openResult = streamingGateway.open("Bearer " + key2.secret(),
                    (ObjectNode) objectMapper.readTree(body));
            assertThat(openResult.isSuccessful()).isTrue();
            openResult.stream().read();
            openResult.stream().close();

            List<ProjectExternalAccessReservation> extRes2 = externalReservations
                    .findByProjectIdAndProviderIdOrderByCreatedAtDesc(project2.getId(), provider.getId());
            assertThat(extRes2).hasSize(1);
            assertThat(extRes2.get(0).getStatus()).isEqualTo(ProjectExternalAccessReservation.ReservationStatus.HELD_UNSETTLED);
        } finally {
            streamServer.stop(0);
        }
    }

    // Scenario 5: SERVICE_TIER_UNKNOWN / FAST_PRICE_MISSING / !isSafeToRetry() timeout -> HELD_UNSETTLED
    @Test
    void unknownServiceTierMissingFastPriceAndAmbiguousTimeoutHoldUnsettledReservations() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch releaseSlowServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int n = calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (n == 3) {
                // Exceeds integration response-timeout-ms=1000 -> SocketTimeoutException (!isSafeToRetry)
                try { releaseSlowServer.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                return;
            }
            String tier = n == 1 ? "unknown_vip_tier" : "fast";
            byte[] response = ("{\"id\":\"r-" + n + "\",\"model\":\"m\",\"service_tier\":\"" + tier + "\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                    + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            Organization org = organizations.save(new Organization("Unsettled Edge Org"));
            ExternalProvider provider = healthyProvider(org, "Edge Provider", server);
            // Standard prices configured, fast prices intentionally null
            ModelDeployment model = deployments.save(ModelDeployment.external(
                    provider.getId(), "edge-m", "edge-m", "Edge Model", 8192, 10,
                    "[\"CHAT_COMPLETION\"]", BigDecimal.ZERO, BigDecimal.valueOf(10_000), Currency.KRW));
            LlmService svc = services.save(new LlmService(org.getId(), "edge-svc", "Edge Svc",
                    FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
            controlPlane.addTarget(svc.getId(), new AdminDtos.CreateTarget(model.getId(), 1, 100, false, null));

            // 5a. SERVICE_TIER_UNKNOWN -> HELD_UNSETTLED
            assertUnsettledOutcome(org, svc, provider, "p-tier", "SERVICE_TIER_UNKNOWN", 200);
            // 5b. FAST_PRICE_MISSING -> HELD_UNSETTLED
            assertUnsettledOutcome(org, svc, provider, "p-fast", "FAST_PRICE_MISSING", 200);
            // 5c. !isSafeToRetry() read timeout -> HELD_UNSETTLED
            assertUnsettledOutcome(org, svc, provider, "p-timeout", "AMBIGUOUS_UPSTREAM_FAILURE", 503);
        } finally {
            releaseSlowServer.countDown();
            server.stop(0);
        }
    }

    private void assertUnsettledOutcome(Organization org, LlmService svc, ExternalProvider provider,
                                        String projName, String expectedReason, int expectedStatus) throws Exception {
        Project p = projects.save(new Project(org.getId(), projName));
        IssuedApiKey key = apiKeys.issue(p.getId(), "k-" + projName, null);
        serviceAccess.save(new ProjectServiceAccess(p.getId(), svc.getId()));
        externalAccessService.decide(p.getId(), provider.getId(), ExternalAccessStatus.APPROVED,
                true, false, BigDecimal.ONE, Currency.KRW, null, null);
        SpendQuota sq = spendQuotas.save(new SpendQuota(org.getId(), SpendQuotaScope.PROJECT, p.getId(),
                "sq-" + projName, Currency.KRW, BigDecimal.ONE, SpendQuotaPeriod.MONTHLY, true, true));

        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + key.secret())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"edge-svc\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().is(expectedStatus));

        List<ProjectExternalAccessReservation> extRes = externalReservations
                .findByProjectIdAndProviderIdOrderByCreatedAtDesc(p.getId(), provider.getId());
        assertThat(extRes).hasSize(1);
        assertThat(extRes.get(0).getStatus()).isEqualTo(ProjectExternalAccessReservation.ReservationStatus.HELD_UNSETTLED);
        assertThat(extRes.get(0).getReason()).isEqualTo(expectedReason);

        List<SpendQuotaReservation> sqRes = spendReservations.findByQuotaIdOrderByCreatedAtDesc(sq.getId());
        assertThat(sqRes).hasSize(1);
        assertThat(sqRes.get(0).getStatus()).isEqualTo(SpendQuotaReservation.ReservationStatus.HELD_UNSETTLED);
        assertThat(sqRes.get(0).getReason()).isEqualTo(expectedReason);
    }

    // Scenario 6: Crash recovery (reclaimExpired frees expired ACTIVE lease, preserves HELD_UNSETTLED until period end)
    @Test
    void crashRecoveryReclaimsExpiredActiveLeasesButKeepsHeldUnsettledUntilPeriodEnd() {
        Organization org = organizations.save(new Organization("Crash Recovery Org"));
        Project project = projects.save(new Project(org.getId(), "crash-proj"));
        ExternalProvider provider = providers.save(new ExternalProvider(org.getId(), ExternalProviderType.OPENAI,
                "Crash Provider", "https://api.example.com/v1", cipher.encrypt("secret")));
        SpendQuota sq = spendQuotas.save(new SpendQuota(org.getId(), SpendQuotaScope.PROJECT, project.getId(),
                "crash-sq", Currency.KRW, BigDecimal.TEN, SpendQuotaPeriod.MONTHLY, true, true));

        Instant now = Instant.now();
        ZonedDateTime utcNow = now.atZone(ZoneOffset.UTC);
        Instant monthStart = utcNow.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
        Instant monthEnd = utcNow.withDayOfMonth(1).plusMonths(1).truncatedTo(ChronoUnit.DAYS).toInstant();

        // Expired ACTIVE reservations (simulating crashed pods)
        ProjectExternalAccessReservation expiredActiveExt = externalReservations.save(new ProjectExternalAccessReservation(
                project.getId(), provider.getId(), UUID.randomUUID(), "req-active",
                BigDecimal.valueOf(5), Currency.KRW, monthStart, now.minusSeconds(30)));
        SpendQuotaReservation expiredActiveSq = spendReservations.save(new SpendQuotaReservation(
                sq.getId(), UUID.randomUUID(), BigDecimal.valueOf(5), Currency.KRW, now.minusSeconds(30)));

        // HELD_UNSETTLED reservations (active within current billing period until monthEnd)
        UUID heldExtKey = UUID.randomUUID();
        ProjectExternalAccessReservation heldExt = externalReservations.save(new ProjectExternalAccessReservation(
                project.getId(), provider.getId(), heldExtKey, "req-held",
                BigDecimal.valueOf(4), Currency.KRW, monthStart, now.plusSeconds(600)));
        providerCapService.holdUnsettled(
                new ProviderCapReservationService.ProviderReservation(heldExtKey, project.getId(), provider.getId(), monthEnd),
                "STREAM_INTERRUPTED_UNSETTLED");

        UUID heldSqKey = UUID.randomUUID();
        SpendQuotaReservation heldSq = spendReservations.save(new SpendQuotaReservation(
                sq.getId(), heldSqKey, BigDecimal.valueOf(4), Currency.KRW, now.plusSeconds(600)));
        spendQuotaService.holdUnsettled(new SpendQuotaService.Reservation(heldSqKey), "STREAM_INTERRUPTED_UNSETTLED");

        // Reclaim at now -> expired ACTIVE become RELEASED, HELD_UNSETTLED stay HELD_UNSETTLED
        providerCapService.reclaimExpired(now);
        spendQuotaService.reclaimExpired(now);

        assertThat(externalReservations.findById(expiredActiveExt.getId()).orElseThrow().getStatus())
                .isEqualTo(ProjectExternalAccessReservation.ReservationStatus.RELEASED);
        assertThat(spendReservations.findById(expiredActiveSq.getId()).orElseThrow().getStatus())
                .isEqualTo(SpendQuotaReservation.ReservationStatus.RELEASED);

        assertThat(externalReservations.findById(heldExt.getId()).orElseThrow().getStatus())
                .isEqualTo(ProjectExternalAccessReservation.ReservationStatus.HELD_UNSETTLED);
        assertThat(spendReservations.findById(heldSq.getId()).orElseThrow().getStatus())
                .isEqualTo(SpendQuotaReservation.ReservationStatus.HELD_UNSETTLED);

        // Advance before period end -> HELD_UNSETTLED STILL held and still counted!
        Instant justBeforePeriodEnd = monthEnd.minusSeconds(1);
        providerCapService.reclaimExpired(justBeforePeriodEnd);
        spendQuotaService.reclaimExpired(justBeforePeriodEnd);
        assertThat(externalReservations.findById(heldExt.getId()).orElseThrow().getStatus())
                .isEqualTo(ProjectExternalAccessReservation.ReservationStatus.HELD_UNSETTLED);
        assertThat(spendReservations.findById(heldSq.getId()).orElseThrow().getStatus())
                .isEqualTo(SpendQuotaReservation.ReservationStatus.HELD_UNSETTLED);
        assertThat(externalReservations.sumReservedAmount(
                project.getId(), provider.getId(), Currency.KRW, monthStart, justBeforePeriodEnd))
                .isEqualByComparingTo("4");
        assertThat(spendReservations.sumActiveAmountByQuotaId(sq.getId(), monthStart, justBeforePeriodEnd))
                .isEqualByComparingTo("4");

        // After period end -> HELD_UNSETTLED no longer blocks the new billing period
        Instant nextPeriod = monthEnd.plusSeconds(1);
        assertThat(externalReservations.sumReservedAmount(
                project.getId(), provider.getId(), Currency.KRW, monthEnd, nextPeriod))
                .isEqualByComparingTo("0");
        assertThat(spendReservations.sumActiveAmountByQuotaId(sq.getId(), monthEnd, nextPeriod))
                .isEqualByComparingTo("0");
    }

    // Scenario 7: Immediate-mode failover revalidation blocks dispatch to candidate 2 if revoked during attempt 1
    @Test
    void immediateModeRevalidatesBeforeFailoverCandidateForJsonAndStreaming() throws Exception {
        for (boolean stream : new boolean[]{false, true}) {
            AtomicInteger callsPrimary = new AtomicInteger();
            AtomicInteger callsFallback = new AtomicInteger();
            Organization org = organizations.save(new Organization("Immediate Reval Org " + stream));
            Project project = projects.save(new Project(org.getId(), "immediate-reval"));
            IssuedApiKey key = apiKeys.issue(project.getId(), "k", null);

            HttpServer fallbackServer = okServer(callsFallback, null);
            ExternalProvider fallbackProvider = healthyProvider(org, "Fallback External", fallbackServer);
            externalAccessService.decide(project.getId(), fallbackProvider.getId(),
                    ExternalAccessStatus.APPROVED, true, true, null, null, null, null);

            HttpServer primaryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            primaryServer.createContext("/v1/chat/completions", exchange -> {
                callsPrimary.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                // Revoke fallback external access mid-flight while primary is failing!
                ProjectExternalAccess accessRow = externalAccess
                        .findByProjectIdAndProviderId(project.getId(), fallbackProvider.getId()).orElseThrow();
                accessRow.decide(ExternalAccessStatus.REVOKED, false, false, null, null, null, null);
                externalAccess.saveAndFlush(accessRow);

                byte[] err = "{\"error\":{\"message\":\"temporarily unavailable\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, err.length);
                exchange.getResponseBody().write(err);
                exchange.close();
            });
            primaryServer.start();
            try {
                InferenceNode node = nodes.save(new InferenceNode(org.getId(), "local-node", null, "DIRECT", null));
                RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO,
                        "http://127.0.0.1:" + primaryServer.getAddress().getPort(), null);
                endpoint.recordHealth(true);
                endpoint = RuntimeTestTrust.internal(endpoints, runtimeTrust, runtimeApprovals, projects, nodes, endpoint);

                ModelDeployment localModel = deployments.save(new ModelDeployment(endpoint.getId(), "local-m", "compat",
                        "Local", null, null, 8192, true, 1, "[\"CHAT_COMPLETION\",\"STREAMING\"]"));
                ModelDeployment externalModel = deployments.save(ModelDeployment.external(fallbackProvider.getId(),
                        "ext-m", "compat", "External", 8192, 10,
                        "[\"CHAT_COMPLETION\",\"STREAMING\"]", BigDecimal.ZERO, BigDecimal.ZERO));

                // Default SchedulingMode is IMMEDIATE
                LlmService svc = services.save(new LlmService(org.getId(), "immediate-svc", "Immediate Svc",
                        FailoverPolicy.COMPATIBLE, RetryPolicy.AGGRESSIVE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
                controlPlane.addTarget(svc.getId(), new AdminDtos.CreateTarget(localModel.getId(), 1, 100, false, null));
                controlPlane.addTarget(svc.getId(), new AdminDtos.CreateTarget(externalModel.getId(), 2, 100, false, null));
                serviceAccess.save(new ProjectServiceAccess(project.getId(), svc.getId()));

                mvc.perform(post("/v1/chat/completions")
                                .header("Authorization", "Bearer " + key.secret())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"model\":\"immediate-svc\",\"stream\":" + stream
                                        + ",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                        .andExpect(status().isTooManyRequests()); // 429 MODEL_AT_CAPACITY from primary 503

                assertThat(callsPrimary).hasValue(1);
                assertThat(callsFallback).hasValue(0);
            } finally {
                primaryServer.stop(0);
                fallbackServer.stop(0);
            }
        }
    }

    // Scenario 8: Admin ProjectQuota ceiling clamps existing values and blocks director from exceeding RPM / monthly tokens
    @Test
    void adminProjectQuotaCeilingClampsExistingQuotaAndBlocksDirectorRelaxation() throws Exception {
        Organization org = organizations.save(new Organization("ProjectQuota Ceiling Org"));
        Project project = projects.save(new Project(org.getId(), "pq-ceiling-proj"));
        String directorToken = createDirectorToken(org, project, "pq-director@test.local");

        // Pre-existing higher RPM and unlimited monthly tokens
        projectQuotas.save(new ProjectQuota(project.getId(), 100, null));

        // Platform admin sets ceiling (20 RPM, 5000 tokens) -> clamps down to 20 / 5000
        mvc.perform(put("/api/admin/platform/projects/{projectId}/quota-ceiling", project.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxRequestsPerMinute\":20,\"maxMonthlyTokenLimit\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestsPerMinute").value(20))
                .andExpect(jsonPath("$.monthlyTokenLimit").value(5000))
                .andExpect(jsonPath("$.maxRequestsPerMinute").value(20))
                .andExpect(jsonPath("$.maxMonthlyTokenLimit").value(5000));

        mvc.perform(get("/api/admin/platform/projects/{projectId}/quota-ceiling", project.getId())
                        .header("X-Admin-Token", "integration-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRequestsPerMinute").value(20))
                .andExpect(jsonPath("$.maxMonthlyTokenLimit").value(5000));

        // Director cannot read platform ceiling endpoint directly
        mvc.perform(get("/api/admin/platform/projects/{projectId}/quota-ceiling", project.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isForbidden());

        // Director sees ceiling in project quota endpoint
        mvc.perform(get("/api/admin/projects/{projectId}/quota", project.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxRequestsPerMinute").value(20))
                .andExpect(jsonPath("$.maxMonthlyTokenLimit").value(5000));

        // Exceeding RPM ceiling -> 403 QUOTA_CEILING_EXCEEDED
        mvc.perform(put("/api/admin/projects/{projectId}/quota", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestsPerMinute\":21,\"monthlyTokenLimit\":4000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUOTA_CEILING_EXCEEDED"));

        // Exceeding or clearing monthlyTokenLimit ceiling -> 403 QUOTA_CEILING_EXCEEDED
        mvc.perform(put("/api/admin/projects/{projectId}/quota", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestsPerMinute\":15,\"monthlyTokenLimit\":5001}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUOTA_CEILING_EXCEEDED"));
        mvc.perform(put("/api/admin/projects/{projectId}/quota", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestsPerMinute\":15}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QUOTA_CEILING_EXCEEDED"));

        // Tightening within ceiling -> 200 OK
        mvc.perform(put("/api/admin/projects/{projectId}/quota", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestsPerMinute\":10,\"monthlyTokenLimit\":2500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestsPerMinute").value(10))
                .andExpect(jsonPath("$.monthlyTokenLimit").value(2500))
                .andExpect(jsonPath("$.maxRequestsPerMinute").value(20))
                .andExpect(jsonPath("$.maxMonthlyTokenLimit").value(5000));
    }

    // Scenario 9: Admin SpendQuota (adminCeiling=true) immutable by director; director CRUD sub-quotas <= minAdminCeiling
    @Test
    void adminSpendQuotaCeilingIsImmutableAndDirectorSubQuotasRespectCeilings() throws Exception {
        Organization org = organizations.save(new Organization("SpendCeiling Org"));
        Project project = projects.save(new Project(org.getId(), "spend-ceiling-proj"));
        IssuedApiKey apiKey = apiKeys.issue(project.getId(), "sub-key", null);
        String directorToken = createDirectorToken(org, project, "sq-director@test.local");

        // Admin creates org ceiling (1000 KRW) and project ceiling (500 KRW)
        MvcResult orgRes = mvc.perform(post("/api/admin/organizations/{orgId}/quotas", org.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Org Cap\",\"scopeType\":\"ORGANIZATION\",\"currency\":\"KRW\",\"limitAmount\":1000,\"period\":\"MONTHLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminCeiling").value(true))
                .andReturn();
        MvcResult projRes = mvc.perform(post("/api/admin/organizations/{orgId}/quotas", org.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Proj Admin Cap\",\"scopeType\":\"PROJECT\",\"scopeId\":\"" + project.getId() + "\",\"currency\":\"KRW\",\"limitAmount\":500,\"period\":\"MONTHLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminCeiling").value(true))
                .andReturn();
        String projCeilingId = objectMapper.readTree(projRes.getResponse().getContentAsString()).path("id").asText();
        assertThat(objectMapper.readTree(orgRes.getResponse().getContentAsString()).path("adminCeiling").asBoolean()).isTrue();

        // Director lists spend quotas & ceilings
        mvc.perform(get("/api/admin/projects/{projectId}/spend-quotas", project.getId())
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.ceilings.length()").value(2))
                .andExpect(jsonPath("$.quotas.length()").value(1))
                .andExpect(jsonPath("$.quotas[0].adminCeiling").value(true));

        // Director cannot modify or delete adminCeiling quota -> 403 ADMIN_CEILING_IMMUTABLE
        mvc.perform(patch("/api/admin/projects/{projectId}/spend-quotas/{quotaId}", project.getId(), projCeilingId)
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitAmount\":900}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_CEILING_IMMUTABLE"));

        mvc.perform(delete("/api/admin/projects/{projectId}/spend-quotas/{quotaId}", project.getId(), projCeilingId)
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_CEILING_IMMUTABLE"));

        // Director cannot create sub-quota exceeding minAdminCeiling (500) -> 403 SPEND_QUOTA_CEILING_EXCEEDED
        mvc.perform(post("/api/admin/projects/{projectId}/spend-quotas", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"PROJECT\",\"scopeId\":\"" + project.getId()
                                + "\",\"name\":\"Too High\",\"period\":\"MONTHLY\",\"currency\":\"KRW\",\"limitAmount\":600}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPEND_QUOTA_CEILING_EXCEEDED"));

        // Director creates project sub-quota 400 <= 500 -> 200 OK (adminCeiling: false)
        mvc.perform(post("/api/admin/projects/{projectId}/spend-quotas", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"PROJECT\",\"scopeId\":\"" + project.getId()
                                + "\",\"name\":\"Director Project Cap\",\"period\":\"MONTHLY\",\"currency\":\"KRW\",\"limitAmount\":400}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminCeiling").value(false))
                .andExpect(jsonPath("$.limitAmount").value(400));

        // Director creates API_KEY sub-quota > project sub-quota (450 > 400) -> 403 SPEND_QUOTA_CEILING_EXCEEDED
        mvc.perform(post("/api/admin/projects/{projectId}/spend-quotas", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"API_KEY\",\"scopeId\":\"" + apiKey.id()
                                + "\",\"name\":\"Key Too High\",\"period\":\"MONTHLY\",\"currency\":\"KRW\",\"limitAmount\":450}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPEND_QUOTA_CEILING_EXCEEDED"));

        // Director creates API_KEY sub-quota 200 <= 400 -> 200, updates to 300 -> 200, rejects 401 -> 403, deletes -> 204
        MvcResult keyQuotaRes = mvc.perform(post("/api/admin/projects/{projectId}/spend-quotas", project.getId())
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"API_KEY\",\"scopeId\":\"" + apiKey.id()
                                + "\",\"name\":\"Key Cap\",\"period\":\"MONTHLY\",\"currency\":\"KRW\",\"limitAmount\":200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminCeiling").value(false))
                .andReturn();
        String keyQuotaId = objectMapper.readTree(keyQuotaRes.getResponse().getContentAsString()).path("id").asText();

        mvc.perform(patch("/api/admin/projects/{projectId}/spend-quotas/{quotaId}", project.getId(), keyQuotaId)
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitAmount\":300}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitAmount").value(300));

        mvc.perform(patch("/api/admin/projects/{projectId}/spend-quotas/{quotaId}", project.getId(), keyQuotaId)
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limitAmount\":401}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SPEND_QUOTA_CEILING_EXCEEDED"));

        mvc.perform(delete("/api/admin/projects/{projectId}/spend-quotas/{quotaId}", project.getId(), keyQuotaId)
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isNoContent());
    }

    private ExternalProvider healthyProvider(Organization org, String name, HttpServer server) {
        ExternalProvider provider = new ExternalProvider(org.getId(), ExternalProviderType.OPENAI, name,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", cipher.encrypt("provider-secret"));
        provider.recordHealth(true);
        return providers.save(provider);
    }

    private HttpServer okServer(AtomicInteger calls, String serviceTier) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            JsonNode req = objectMapper.readTree(exchange.getRequestBody().readAllBytes());
            boolean stream = req.path("stream").asBoolean(false);
            String tierField = serviceTier == null ? "" : "\"service_tier\":\"" + serviceTier + "\",";
            byte[] response = stream
                    ? ("data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)
                    : ("{\"id\":\"ok\",\"model\":\"m\"," + tierField
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                        + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private String createDirectorToken(Organization org, Project project, String email) {
        AppUser director = new AppUser(email, "$2a$10$dummyhash000000000000000000000000000000000000000000",
                false, AccountTier.PROJECT_DIRECTOR, null);
        director.assignPendingTotpSecret("enc");
        director.activateTotp(1L, Instant.now());
        director.enforceProjectScope();
        director = users.save(director);
        organizationMembers.save(new OrganizationMember(org.getId(), director.getId(), OrganizationRole.DEVELOPER));
        projectMemberships.save(new ProjectMembership(project.getId(), director.getId(), ProjectMembership.Role.MANAGER));
        AuthRefreshToken session = refreshTokens.save(new AuthRefreshToken(
                director.getId(),
                UUID.randomUUID().toString().replace("-", "") + "0".repeat(32),
                Instant.now().plusSeconds(3600),
                true));
        return accessTokenService.issue(director, session.getId(), true);
    }
}
