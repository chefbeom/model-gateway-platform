package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.routing.ActiveRequestRegistry;
import com.aiconnect.llmgateway.scheduling.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:aiconnect_scheduling;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class GatewaySchedulingIntegrationTest {
    @LocalServerPort int port;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyService keys;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired ModelDeploymentRepository models;
    @Autowired LlmServiceRepository services;
    @Autowired ServiceTargetRepository targets;
    @Autowired ProjectServiceAccessRepository access;
    @Autowired LlmRequestRepository requests;
    @Autowired SchedulingPolicyService policies;
    @Autowired SchedulingTraceService traces;
    @Autowired ActiveRequestRegistry active;
    @Autowired ObjectMapper mapper;
    @Autowired ExternalProviderRepository providers;
    @Autowired ProjectExternalAccessRepository externalAccess;
    @Autowired com.aiconnect.llmgateway.service.SecretCipher cipher;
    @Autowired com.aiconnect.llmgateway.playground.ModelPlaygroundService playground;
    @Autowired com.aiconnect.llmgateway.dataprotection.DataProtectionPolicyService protection;
    @Autowired com.aiconnect.llmgateway.retention.RequestContentService retention;
    @Autowired com.aiconnect.llmgateway.beta.BetaServiceRepository betaServices;
    @Autowired com.aiconnect.llmgateway.beta.BetaMemberRepository betaMembers;
    @Autowired com.aiconnect.llmgateway.beta.BetaAdministration betaAdmin;
    @Autowired com.aiconnect.llmgateway.quota.QuotaService quotaService;
    private HttpServer runtime;
    private Fixture fixture;
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicInteger cloudCalls=new AtomicInteger();

    @BeforeEach void setUp() throws Exception {
        runtime=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        runtime.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();var body=mapper.readTree(exchange.getRequestBody());boolean stream=body.path("stream").asBoolean();
            if(body.path("model").asText().equals("cloud"))cloudCalls.incrementAndGet();
            String response=stream?"data: {\"model\":\"physical\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n":"{\"model\":\"physical\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}";
            if(body.path("messages").path(0).path("content").asText().equals("empty response test")) {
                response=stream ? "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"PRIVATE_THINKING\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"
                        : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"PRIVATE_THINKING\"},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":19,\"completion_tokens\":512}}";
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });runtime.start();
        var organization=organizations.save(new Organization("Queue "+UUID.randomUUID()));
        var project=projects.save(new Project(organization.getId(),"client"));var key=keys.issue(project.getId(),"client",null);
        var node=nodes.save(new InferenceNode(organization.getId(),"runtime",null,"DIRECT",null));
        var endpoint=new RuntimeEndpoint(node.getId(),RuntimeType.LM_STUDIO,"http://127.0.0.1:"+runtime.getAddress().getPort(),null);
        endpoint.recordHealth(true);endpoint=endpoints.save(endpoint);
        var model=models.save(new ModelDeployment(endpoint.getId(),"physical","physical",null,null,8192,true,1,"[\"STREAMING\"]"));
        var service=services.save(new LlmService(organization.getId(),"queue-service","Queue",FailoverPolicy.COMPATIBLE,false,"[]",java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO));
        targets.save(new ServiceTarget(service.getId(),model.getId(),1,100,false,null));access.save(new ProjectServiceAccess(project.getId(),service.getId()));
        policies.save(service.getId(),new SchedulingSettings(SchedulingMode.LOCAL_ONLY_QUEUE,0,5000,10,1024*1024,10));
        fixture=new Fixture(project,key,endpoint,model,service);
    }
    @AfterEach void stop(){runtime.stop(0);}
    private CompletableFuture<HttpResponse<String>> send(boolean stream) {
        return send(fixture.key.secret(), stream);
    }
    private CompletableFuture<HttpResponse<String>> send(String secret, boolean stream) {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/chat/completions"))
                .header("Authorization","Bearer "+secret).header("Content-Type","application/json")
                .timeout(java.time.Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"queue-service\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":"+stream+"}")).build();
        return HttpClient.newHttpClient().sendAsync(request,HttpResponse.BodyHandlers.ofString());
    }
    private void hold(){assertThat(active.tryAcquire(fixture.model.getId(),1,fixture.endpoint.getId(),1,null)).isTrue();}
    private void release(){active.release(fixture.model.getId(),fixture.endpoint.getId());}
    private void awaitQueued() throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(active.snapshot(fixture.service.getId()).queuedRequests()==0&&System.nanoTime()<end)Thread.sleep(10);assertThat(active.snapshot(fixture.service.getId()).queuedRequests()).isEqualTo(1);}
    @Test void jsonRequestWaitsAsynchronouslyAndRecordsSeparateQueueAndExecutionTimes() throws Exception {
        retention.setPolicy(fixture.project.getId(),com.aiconnect.llmgateway.retention.ContentRetentionMode.FULL_ENCRYPTED);
        hold();var response=send(false);awaitQueued();assertThat(response.isDone()).isFalse();assertThat(calls).hasValue(0);
        release();var result=response.get(8,TimeUnit.SECONDS);assertThat(result.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(result.body()).path("model").asText()).as(result.body()).isEqualTo("queue-service");
        String id=result.headers().firstValue("X-Request-Id").orElseThrow();var trace=traces.view(id);
        assertThat(trace.phase()).isEqualTo("SUCCEEDED");assertThat(trace.dispatchReason()).isEqualTo("QUEUED_LOCAL_AVAILABLE");
        assertThat(trace.queueWaitMs()).isPositive();assertThat(trace.executionMs()).isNotNull();
        assertThat(active.runtimeCount(fixture.endpoint.getId())).isZero();assertThat(calls).hasValue(1);
        assertThat(retention.read(fixture.project.getId(),id).response()).contains("hello");
    }
    @Test void streamingRequestWaitsWithoutFakeContentThenRelaysActualSseAndReleasesCapacity() throws Exception {
        hold();var response=send(true);awaitQueued();assertThat(calls).hasValue(0);assertThat(response.isDone()).isFalse();
        release();var result=response.get(8,TimeUnit.SECONDS);assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("hello","[DONE]","queue-service").doesNotContain("physical");
        assertThat(active.runtimeCount(fixture.endpoint.getId())).isZero();assertThat(calls).hasValue(1);
        assertThat(traces.view(result.headers().firstValue("X-Request-Id").orElseThrow()).phase()).isEqualTo("SUCCEEDED");
    }
    @Test void revokedKeyWhileWaitingIsRejectedWithoutCallingRuntime() throws Exception {
        hold();var response=send(false);awaitQueued();keys.revoke(fixture.key.id());release();
        var result=response.get(8,TimeUnit.SECONDS);assertThat(result.statusCode()).isEqualTo(401);
        assertThat(mapper.readTree(result.body()).path("error").path("code").asText()).as(result.body()).isEqualTo("INVALID_API_KEY");
        assertThat(calls).hasValue(0);assertThat(active.snapshot(fixture.service.getId()).queuedRequests()).isZero();
    }
    @Test void pausedBetaIsRejectedForJsonAndStreamingBeforeAnyInference() throws Exception {
        var beta=betaServices.save(new com.aiconnect.llmgateway.beta.BetaService(fixture.project.getOrganizationId(),fixture.service.getId()));
        betaMembers.save(new com.aiconnect.llmgateway.beta.BetaMember(beta.getId(),fixture.project.getId(),"beta@example.invalid"));
        for(boolean streaming : new boolean[]{false,true}) {
            var result=send(streaming).get(8,TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(403);
            assertThat(mapper.readTree(result.body()).path("error").path("code").asText()).isEqualTo("BETA_ACCESS_DISABLED");
        }
        assertThat(calls).hasValue(0); assertThat(cloudCalls).hasValue(0);
    }
    @Test void activeBetaJsonAndStreamingUseOnlyLocalWithSharedLimits() throws Exception {
        var beta=new com.aiconnect.llmgateway.beta.BetaService(fixture.project.getOrganizationId(),fixture.service.getId());
        beta.setMode("LOCAL_ONLY"); beta=betaServices.save(beta);
        betaMembers.save(new com.aiconnect.llmgateway.beta.BetaMember(beta.getId(),fixture.project.getId(),"active@example.invalid"));
        var provider=new ExternalProvider(fixture.project.getOrganizationId(),ExternalProviderType.OPENAI,"Beta Cloud",fixture.endpoint.getBaseUrl()+"/v1",cipher.encrypt("test-secret"));
        provider.recordHealth(true); provider=providers.save(provider);
        var cloud=models.save(ModelDeployment.external(provider.getId(),"cloud","cloud","Cloud",8192,20,"[]",java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO));
        targets.save(new ServiceTarget(fixture.service.getId(),cloud.getId(),2,100,false,null));
        var approval=new ProjectExternalAccess(fixture.project.getId(),provider.getId(),null,"test");
        approval.decide(ExternalAccessStatus.APPROVED,true,true,null,null,null); externalAccess.save(approval);
        var previous=org.springframework.test.util.ReflectionTestUtils.getField(quotaService,"rateLimits");
        var redis=org.mockito.Mockito.mock(com.aiconnect.llmgateway.quota.RedisRateLimitStore.class);
        org.mockito.Mockito.when(redis.tryAcquire(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(quotaService,"rateLimits",redis);
        try {
            for(boolean streaming : new boolean[]{false,true}) assertThat(send(streaming).get(8,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(calls).hasValue(2); assertThat(cloudCalls).hasValue(0);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(quotaService,"rateLimits",previous); }
    }
    @Test void suspendingBetaMemberWhileQueuedRejectsWithoutCallingRuntimeAndReleasesSlot() throws Exception {
        var betaProject=projects.save(new Project(fixture.project.getOrganizationId(),"beta-suspend-client"));
        betaProject.configureExternalAiBlocked(true);
        betaProject=projects.save(betaProject);
        access.save(new ProjectServiceAccess(betaProject.getId(),fixture.service.getId()));
        var betaKey=keys.issue(betaProject.getId(),"beta-suspend-key",null);
        var beta=new com.aiconnect.llmgateway.beta.BetaService(fixture.project.getOrganizationId(),fixture.service.getId());
        beta.setMode("LOCAL_ONLY"); beta=betaServices.save(beta);
        var member=betaMembers.save(new com.aiconnect.llmgateway.beta.BetaMember(beta.getId(),betaProject.getId(),"queued-suspend@example.invalid"));
        var previous=org.springframework.test.util.ReflectionTestUtils.getField(quotaService,"rateLimits");
        var redis=org.mockito.Mockito.mock(com.aiconnect.llmgateway.quota.RedisRateLimitStore.class);
        org.mockito.Mockito.when(redis.tryAcquire(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(quotaService,"rateLimits",redis);
        boolean held=true;
        hold();
        try {
            var response=send(betaKey.secret(),false);
            awaitQueued();
            assertThat(calls).hasValue(0);
            betaAdmin.suspend(beta.getId(),member.getId());
            release(); held=false;
            var result=response.get(8,TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(403);
            assertThat(mapper.readTree(result.body()).path("error").path("code").asText()).as(result.body()).isEqualTo("BETA_ACCESS_DISABLED");
            assertThat(calls).hasValue(0); assertThat(cloudCalls).hasValue(0);
            assertThat(active.snapshot(fixture.service.getId()).queuedRequests()).isZero();
            assertThat(active.count(fixture.model.getId())).isZero();
            assertThat(active.runtimeCount(fixture.endpoint.getId())).isZero();
            String requestId=result.headers().firstValue("X-Request-Id").orElseThrow();
            var trace=traces.view(requestId);
            assertThat(trace.phase()).isEqualTo("FAILED");
            assertThat(trace.errorCode()).isEqualTo("BETA_ACCESS_DISABLED");
            var normal=send(false).get(8,TimeUnit.SECONDS);
            assertThat(normal.statusCode()).isEqualTo(200);
            assertThat(calls).hasValue(1);
            assertThat(active.count(fixture.model.getId())).isZero();
            assertThat(active.runtimeCount(fixture.endpoint.getId())).isZero();
        } finally {
            if(held) release();
            org.springframework.test.util.ReflectionTestUtils.setField(quotaService,"rateLimits",previous);
        }
    }
    @Test void pausingBetaServiceWhileQueuedRejectsStreamingWithoutCallingRuntimeAndReleasesSlot() throws Exception {
        var betaProject=projects.save(new Project(fixture.project.getOrganizationId(),"beta-pause-client"));
        betaProject.configureExternalAiBlocked(true);
        betaProject=projects.save(betaProject);
        access.save(new ProjectServiceAccess(betaProject.getId(),fixture.service.getId()));
        var betaKey=keys.issue(betaProject.getId(),"beta-pause-key",null);
        var beta=new com.aiconnect.llmgateway.beta.BetaService(fixture.project.getOrganizationId(),fixture.service.getId());
        beta.setMode("LOCAL_ONLY"); beta=betaServices.save(beta);
        betaMembers.save(new com.aiconnect.llmgateway.beta.BetaMember(beta.getId(),betaProject.getId(),"queued-pause@example.invalid"));
        var previous=org.springframework.test.util.ReflectionTestUtils.getField(quotaService,"rateLimits");
        var redis=org.mockito.Mockito.mock(com.aiconnect.llmgateway.quota.RedisRateLimitStore.class);
        org.mockito.Mockito.when(redis.tryAcquire(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(quotaService,"rateLimits",redis);
        boolean held=true;
        hold();
        try {
            var response=send(betaKey.secret(),true);
            awaitQueued();
            assertThat(calls).hasValue(0);
            betaAdmin.mode(beta.getId(),"PAUSED");
            release(); held=false;
            var result=response.get(8,TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(403);
            assertThat(mapper.readTree(result.body()).path("error").path("code").asText()).as(result.body()).isEqualTo("BETA_ACCESS_DISABLED");
            assertThat(calls).hasValue(0); assertThat(cloudCalls).hasValue(0);
            assertThat(active.snapshot(fixture.service.getId()).queuedRequests()).isZero();
            assertThat(active.count(fixture.model.getId())).isZero();
            assertThat(active.runtimeCount(fixture.endpoint.getId())).isZero();
            String requestId=result.headers().firstValue("X-Request-Id").orElseThrow();
            var trace=traces.view(requestId);
            assertThat(trace.phase()).isEqualTo("FAILED");
            assertThat(trace.errorCode()).isEqualTo("BETA_ACCESS_DISABLED");
            var normal=send(true).get(8,TimeUnit.SECONDS);
            assertThat(normal.statusCode()).isEqualTo(200);
            assertThat(calls).hasValue(1);
            assertThat(active.count(fixture.model.getId())).isZero();
            assertThat(active.runtimeCount(fixture.endpoint.getId())).isZero();
        } finally {
            if(held) release();
            org.springframework.test.util.ReflectionTestUtils.setField(quotaService,"rateLimits",previous);
        }
    }
    @Test void timeoutReturnsExplicitErrorAndDoesNotSendUpstream() throws Exception {
        policies.save(fixture.service.getId(),new SchedulingSettings(SchedulingMode.LOCAL_ONLY_QUEUE,0,150,10,1024*1024,10));
        hold();try {var response=send(false).get(8,TimeUnit.SECONDS);assertThat(response.statusCode()).isEqualTo(503);
            assertThat(mapper.readTree(response.body()).path("error").path("code").asText()).as(response.body()).isEqualTo("QUEUE_WAIT_TIMEOUT");assertThat(calls).hasValue(0);
        }finally{release();}
    }
    @Test void enablingProjectExternalBoundaryWhileQueuedPreventsSpilloverAfterLocalBudget() throws Exception {
        var provider=new ExternalProvider(fixture.project.getOrganizationId(),ExternalProviderType.OPENAI,"Cloud",fixture.endpoint.getBaseUrl()+"/v1",cipher.encrypt("not-a-real-secret"));provider.recordHealth(true);provider=providers.save(provider);
        var cloud=models.save(ModelDeployment.external(provider.getId(),"cloud","cloud","Cloud",8192,20,"[]",java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO));
        targets.save(new ServiceTarget(fixture.service.getId(),cloud.getId(),2,100,false,null));
        var approval=new ProjectExternalAccess(fixture.project.getId(),provider.getId(),null,"test");approval.decide(ExternalAccessStatus.APPROVED,true,true,null,null,null);externalAccess.save(approval);
        policies.save(fixture.service.getId(),new SchedulingSettings(SchedulingMode.LOCAL_FIRST_WAIT,150,5000,10,1024*1024,10));
        hold();var response=send(false);awaitQueued();
        var project=projects.findById(fixture.project.getId()).orElseThrow();project.configureExternalAiBlocked(true);projects.save(project);
        Thread.sleep(650);assertThat(response.isDone()).isFalse();assertThat(cloudCalls).hasValue(0);
        release();var result=response.get(8,TimeUnit.SECONDS);assertThat(result.statusCode()).isEqualTo(200);assertThat(cloudCalls).hasValue(0);
        var audit=requests.findByRequestId(result.headers().firstValue("X-Request-Id").orElseThrow()).orElseThrow();
        assertThat(audit.getDataExternalAllowed()).isFalse();assertThat(audit.getFinalProviderType()).isEqualTo("LOCAL");
    }
    @Test void directModelTestCannotBypassSharedRuntimeCapacity() throws Exception {
        hold();try {
            var body=mapper.createObjectNode().put("targetId",fixture.model.getId().toString()).put("stream",false);
            body.putArray("messages").addObject().put("role","user").put("content","hello");
            assertThatThrownBy(()->playground.chat(fixture.project.getOrganizationId(),body)).isInstanceOf(com.aiconnect.llmgateway.web.ApiException.class)
                    .satisfies(e->assertThat(((com.aiconnect.llmgateway.web.ApiException)e).getCode()).isEqualTo("MODEL_AT_CAPACITY"));
            assertThat(calls).hasValue(0);assertThat(active.runtimeCount(fixture.endpoint.getId())).isEqualTo(1);
        }finally{release();}
    }
    @Test void playgroundHttpEnforcesOrganizationProtectionBeforeSendingExternalRequests() throws Exception {
        var provider=new ExternalProvider(fixture.project.getOrganizationId(),ExternalProviderType.OPENAI,"Protected Cloud",fixture.endpoint.getBaseUrl()+"/v1",cipher.encrypt("test-secret"));
        provider.recordHealth(true); provider=providers.save(provider);
        var cloud=models.save(ModelDeployment.external(provider.getId(),"cloud","cloud","Cloud",8192,20,"[]",java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO));
        var org=fixture.project.getOrganizationId();
        protection.save(org,com.aiconnect.llmgateway.dataprotection.DataProtectionScopeType.ORGANIZATION,org,
                new com.aiconnect.llmgateway.dataprotection.DataProtectionPolicyService.PolicyValues(
                        com.aiconnect.llmgateway.dataprotection.DataProtectionMode.ENFORCE,
                        com.aiconnect.llmgateway.dataprotection.DataProtectionLevel.STRICT,
                        com.aiconnect.llmgateway.dataprotection.DataProtectionAction.LOCAL_ONLY,
                        false,false,true,false,false,false,"[]"));
        for(boolean streaming : new boolean[]{false,true}) {
            var body=mapper.createObjectNode().put("targetId",cloud.getId().toString()).put("stream",streaming);
            body.putArray("messages").addObject().put("role","user").put("content","user@example.com");
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/admin/organizations/"+org+"/playground/chat"))
                    .header("X-Admin-Token","integration-admin-token").header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            assertThat(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
        assertThat(cloudCalls).hasValue(0);
    }
    @Test void playgroundEmptyJsonPreservesHttpAndRecordsSafeDiagnosisInHistory() {
        var body=mapper.createObjectNode().put("targetId",fixture.model.getId().toString()).put("stream",false).put("maxCompletionTokens",512);
        body.putArray("messages").addObject().put("role","user").put("content","empty response test");
        var result=playground.chat(fixture.project.getOrganizationId(),body);
        assertThat(result.statusCode()).isEqualTo(200);
        var trace=playground.requests(fixture.project.getOrganizationId()).stream().filter(t->t.requestId().equals(result.requestId())).findFirst().orElseThrow();
        assertThat(trace.status()).isEqualTo("FAILED");assertThat(trace.errorCode()).isEqualTo("EMPTY_MODEL_RESPONSE");
        var turn=playground.conversation(fixture.project.getOrganizationId(),result.conversationId()).turns().get(1);
        assertThat(turn.status()).isEqualTo("FAILED");assertThat(turn.httpStatus()).isEqualTo(200);
        assertThat(turn.content()).isEmpty();assertThat(turn.finishReason()).isEqualTo("length");
        assertThat(turn.reasoningPresent()).isTrue();assertThat(turn.outputLimit()).isEqualTo(512);
        assertThat(turn.toString()).doesNotContain("PRIVATE_THINKING");
    }
    @Test void playgroundEmptyStreamRetainsSafeMetadataAndMarksTraceFailed() throws Exception {
        var body=mapper.createObjectNode().put("targetId",fixture.model.getId().toString()).put("stream",true).put("maxCompletionTokens",512);
        body.putArray("messages").addObject().put("role","user").put("content","empty response test");
        var result=playground.chat(fixture.project.getOrganizationId(),body);
        var output=new java.io.ByteArrayOutputStream();result.streaming().writeTo(output);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("[DONE]");
        var trace=playground.requests(fixture.project.getOrganizationId()).stream().filter(t->t.requestId().equals(result.requestId())).findFirst().orElseThrow();
        assertThat(trace.status()).isEqualTo("FAILED");assertThat(trace.httpStatus()).isEqualTo(200);
        assertThat(trace.errorCode()).isEqualTo("EMPTY_MODEL_RESPONSE");
        var turn=playground.conversation(fixture.project.getOrganizationId(),result.conversationId()).turns().get(1);
        assertThat(turn.finishReason()).isEqualTo("length");assertThat(turn.reasoningPresent()).isTrue();
        assertThat(turn.outputLimit()).isEqualTo(512);assertThat(turn.toString()).doesNotContain("PRIVATE_THINKING");
    }
    private record Fixture(Project project,IssuedApiKey key,RuntimeEndpoint endpoint,ModelDeployment model,LlmService service){}
}
