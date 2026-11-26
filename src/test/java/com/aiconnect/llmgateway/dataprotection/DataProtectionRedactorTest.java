package com.aiconnect.llmgateway.dataprotection;

import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.gateway.ResponseCacheService;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.runtime.InferenceRuntimeClient;
import com.aiconnect.llmgateway.runtime.OpenAiRuntimeClient;
import com.aiconnect.llmgateway.security.RuntimeTrustAttestation;
import com.aiconnect.llmgateway.security.RuntimeTrustAttestationRepository;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataProtectionRedactorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EffectiveDataProtectionPolicy balancedPolicy = new EffectiveDataProtectionPolicy(
            DataProtectionMode.ENFORCE,
            DataProtectionLevel.BALANCED,
            DataProtectionAction.REDACT_RESTORE,
            true,
            true,
            true,
            true,
            false,
            true,
            List.of(),
            List.of("SERVICE")
    );

    @Test
    void redactsKoreanPiiAndRestoresOriginalValuesAcrossJsonAndCompactAliases() throws Exception {
        DataProtectionRedactor redactor = new DataProtectionRedactor(objectMapper);
        LlmService service = new LlmService(
                UUID.randomUUID(), "svc-key", "Svc", FailoverPolicy.COMPATIBLE,
                RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);

        ObjectNode request = (ObjectNode) objectMapper.readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"홍길동 965034-3231111 , 01012344321 어제 학교를 갔다\"}]}");

        DataProtectionRedactor.RedactionVault vault = redactor.redactRequest(
                request, balancedPolicy, service, false);

        String outbound = request.path("messages").get(0).path("content").asText();
        assertThat(outbound).isEqualTo("김가명 123456-7890123 , 010-0000-0000 어제 학교를 갔다");
        assertThat(outbound).contains("어제 학교를 갔다");
        assertThat(outbound).doesNotContain("965034-3231111", "01012344321", "홍길동");

        String restoredCompact = vault.restoreText("요청자 김가명 (1234567890123 / 01000000000)은 어제 학교를 갔다");
        assertThat(restoredCompact).isEqualTo("요청자 홍길동 (965034-3231111 / 01012344321)은 어제 학교를 갔다");

        ObjectNode externalReply = (ObjectNode) objectMapper.readTree(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"김가명(123456-7890123, 010-0000-0000) 기록 확인 완료\"}}]}");
        vault.restoreResponseNode(externalReply);
        assertThat(externalReply.path("choices").get(0).path("message").path("content").asText())
                .isEqualTo("홍길동(965034-3231111, 01012344321) 기록 확인 완료");
    }

    @Test
    void respectsMaskPersonNamesToggleWhenDisabled() throws Exception {
        DataProtectionRedactor redactor = new DataProtectionRedactor(objectMapper);
        LlmService service = new LlmService(
                UUID.randomUUID(), "svc-key", "Svc", FailoverPolicy.COMPATIBLE,
                RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
        service.configureGuardrailMasker(null, false, false);

        ObjectNode request = (ObjectNode) objectMapper.readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"홍길동 965034-3231111 , 01012344321 어제 학교를 갔다\"}]}");
        redactor.redactRequest(request, balancedPolicy, service, false);

        assertThat(request.path("messages").get(0).path("content").asText())
                .isEqualTo("홍길동 123456-7890123 , 010-0000-0000 어제 학교를 갔다");
    }

    @Test
    void streamingRestorerReassemblesSurrogatesSplitAcrossSseChunks() throws Exception {
        DataProtectionRedactor redactor = new DataProtectionRedactor(objectMapper);
        ObjectNode request = (ObjectNode) objectMapper.readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"홍길동 965034-3231111 , 01012344321 어제 학교를 갔다\"}]}");
        DataProtectionRedactor.RedactionVault vault = redactor.redactRequest(
                request, balancedPolicy, null, false);

        String ssePayload = ""
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"김\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"가명 123456-\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"7890123 / 010-000\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"0-0000 어제\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\" 학교를 갔다\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";

        try (SseRedactionRestoringInputStream stream = new SseRedactionRestoringInputStream(
                new ByteArrayInputStream(ssePayload.getBytes(StandardCharsets.UTF_8)),
                objectMapper,
                vault)) {
            List<Byte> bytes = new ArrayList<>();
            byte[] buf = new byte[5];
            int n;
            while ((n = stream.read(buf, 0, buf.length)) != -1) {
                for (int i = 0; i < n; i++) bytes.add(buf[i]);
            }
            byte[] all = new byte[bytes.size()];
            for (int i = 0; i < bytes.size(); i++) all[i] = bytes.get(i);
            String rewritten = new String(all, StandardCharsets.UTF_8);

            StringBuilder combined = new StringBuilder();
            for (String line : rewritten.split("\n")) {
                if (!line.startsWith("data:") || line.contains("[DONE]")) continue;
                JsonNode chunk = objectMapper.readTree(line.substring(5).trim());
                combined.append(chunk.path("choices").path(0).path("delta").path("content").asText(""));
            }
            assertThat(combined.toString()).isEqualTo("홍길동 965034-3231111 / 01012344321 어제 학교를 갔다");
            assertThat(combined.toString()).doesNotContain("123456-7890123", "010-0000-0000", "김가명");
        }
    }

    @Test
    void enforcesTrustPolicyOnMaskerDeploymentsAllowingLocalAndZeroRetentionExternal() {
        ModelDeploymentRepository deployments = mock(ModelDeploymentRepository.class);
        RuntimeEndpointRepository endpoints = mock(RuntimeEndpointRepository.class);
        ExternalProviderRepository providers = mock(ExternalProviderRepository.class);
        InferenceNodeRepository nodes = mock(InferenceNodeRepository.class);
        RuntimeTrustAttestationRepository trustAttestations = mock(RuntimeTrustAttestationRepository.class);
        DataProtectionRedactor redactor = new DataProtectionRedactor(
                objectMapper, deployments, endpoints, providers, nodes, trustAttestations,
                mock(InferenceRuntimeClient.class), mock(OpenAiRuntimeClient.class));

        UUID orgId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID localEndpointId = UUID.randomUUID();
        UUID localDepId = UUID.randomUUID();
        UUID verifiedProviderId = UUID.randomUUID();
        UUID verifiedExtDepId = UUID.randomUUID();
        UUID unverifiedProviderId = UUID.randomUUID();
        UUID unverifiedExtDepId = UUID.randomUUID();

        InferenceNode node = mock(InferenceNode.class);
        when(node.getOrganizationId()).thenReturn(orgId);
        when(nodes.findById(nodeId)).thenReturn(Optional.of(node));

        RuntimeEndpoint localEndpoint = mock(RuntimeEndpoint.class);
        when(localEndpoint.getId()).thenReturn(localEndpointId);
        when(localEndpoint.getNodeId()).thenReturn(nodeId);
        when(localEndpoint.isEnabled()).thenReturn(true);
        when(localEndpoint.getBaseUrl()).thenReturn("http://127.0.0.1:11434/v1");
        when(endpoints.findById(localEndpointId)).thenReturn(Optional.of(localEndpoint));
        RuntimeTrustAttestation internalAtt = mock(RuntimeTrustAttestation.class);
        when(internalAtt.getClassification()).thenReturn(RuntimeTrustAttestation.Classification.INTERNAL);
        when(internalAtt.validFor(org.mockito.ArgumentMatchers.eq("http://127.0.0.1:11434/v1"), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(trustAttestations.findById(localEndpointId)).thenReturn(Optional.of(internalAtt));

        ModelDeployment localDep = mock(ModelDeployment.class);
        when(localDep.isEnabled()).thenReturn(true);
        when(localDep.isArchived()).thenReturn(false);
        when(localDep.isExternal()).thenReturn(false);
        when(localDep.getRuntimeEndpointId()).thenReturn(localEndpointId);
        when(deployments.findById(localDepId)).thenReturn(Optional.of(localDep));

        ExternalProvider verifiedProvider = mock(ExternalProvider.class);
        when(verifiedProvider.getOrganizationId()).thenReturn(orgId);
        when(verifiedProvider.isEnabled()).thenReturn(true);
        when(verifiedProvider.isZeroRetentionVerified()).thenReturn(true);
        when(providers.findById(verifiedProviderId)).thenReturn(Optional.of(verifiedProvider));

        ModelDeployment verifiedExtDep = mock(ModelDeployment.class);
        when(verifiedExtDep.isEnabled()).thenReturn(true);
        when(verifiedExtDep.isArchived()).thenReturn(false);
        when(verifiedExtDep.isExternal()).thenReturn(true);
        when(verifiedExtDep.getExternalProviderId()).thenReturn(verifiedProviderId);
        when(deployments.findById(verifiedExtDepId)).thenReturn(Optional.of(verifiedExtDep));

        ExternalProvider unverifiedProvider = mock(ExternalProvider.class);
        when(unverifiedProvider.getOrganizationId()).thenReturn(orgId);
        when(unverifiedProvider.isEnabled()).thenReturn(true);
        when(unverifiedProvider.isZeroRetentionVerified()).thenReturn(false);
        when(providers.findById(unverifiedProviderId)).thenReturn(Optional.of(unverifiedProvider));

        ModelDeployment unverifiedExtDep = mock(ModelDeployment.class);
        when(unverifiedExtDep.isEnabled()).thenReturn(true);
        when(unverifiedExtDep.isArchived()).thenReturn(false);
        when(unverifiedExtDep.isExternal()).thenReturn(true);
        when(unverifiedExtDep.getExternalProviderId()).thenReturn(unverifiedProviderId);
        when(deployments.findById(unverifiedExtDepId)).thenReturn(Optional.of(unverifiedExtDep));

        assertThatCode(() -> redactor.assertTrustedMaskerDeployment(orgId, localDepId)).doesNotThrowAnyException();
        assertThatCode(() -> redactor.assertTrustedMaskerDeployment(orgId, verifiedExtDepId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> redactor.assertTrustedMaskerDeployment(orgId, unverifiedExtDepId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getCode()).isEqualTo("UNTRUSTED_MASKER_DEPLOYMENT");
                });
    }

    @Test
    void incrementsPromptVersionAndKeysResponseCacheByVersionAndGuardrailAction() throws Exception {
        LlmService service = new LlmService(
                UUID.randomUUID(), "svc-key", "Svc", FailoverPolicy.COMPATIBLE,
                RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "id", UUID.randomUUID());

        assertThat(service.getPromptVersion()).isEqualTo(1);
        service.configureSystemPromptTemplate("항상 3줄로 요약하세요.", false);
        assertThat(service.getPromptVersion()).isEqualTo(2);

        ObjectNode request = (ObjectNode) objectMapper.readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"테스트 질문\"}]}");
        service.applySystemPromptTemplate(request);
        assertThat(request.path("messages").size()).isEqualTo(2);
        assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo("항상 3줄로 요약하세요.");

        ResponseCacheService cache = new ResponseCacheService(objectMapper);
        String k1 = cache.cacheKey(service.getOrganizationId(), service, DataProtectionAction.REDACT_RESTORE, request);
        ObjectNode cachedPayload = (ObjectNode) objectMapper.readTree("{\"id\":\"chatcmpl-cached\"}");
        cache.put(k1, cachedPayload, 60);
        assertThat(cache.get(k1)).isPresent();

        service.configureSystemPromptTemplate("다른 시스템 프롬프트입니다.", false);
        assertThat(service.getPromptVersion()).isEqualTo(3);
        String k2 = cache.cacheKey(service.getOrganizationId(), service, DataProtectionAction.REDACT_RESTORE, request);
        String k3 = cache.cacheKey(service.getOrganizationId(), service, DataProtectionAction.REDACT, request);
        assertThat(k2).isNotEqualTo(k1);
        assertThat(k3).isNotEqualTo(k1);
    }

    @Test
    void redactsPiiInsideNonBinaryMediaFieldsWhilePreservingStrictBase64Payloads() throws Exception {
        DataProtectionRedactor redactor = new DataProtectionRedactor(objectMapper);
        ObjectNode request = (ObjectNode) objectMapper.readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\",\"attachment\":\"홍길동 010-1234-5678\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}],\"data\":\"secret sk-proj-abcdefghijklmnopqrstuvwx\"}");

        DataProtectionRedactor.RedactionVault vault = redactor.redactRequest(request, balancedPolicy, null, false);

        assertThat(request.path("messages").get(0).path("attachment").asText()).doesNotContain("홍길동", "010-1234-5678");
        assertThat(request.path("data").asText()).doesNotContain("sk-proj-abcdefghijklmnopqrstuvwx");
        assertThat(request.path("messages").get(0).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,AAAA");
        assertThat(vault.entries()).isNotEmpty();
    }

    @Test
    void maskerLlmProtectsRoleFieldPreventSurrogateOverwriteAndRespectsExternalFlag() throws Exception {
        ModelDeploymentRepository deployments = mock(ModelDeploymentRepository.class);
        RuntimeEndpointRepository endpoints = mock(RuntimeEndpointRepository.class);
        ExternalProviderRepository providers = mock(ExternalProviderRepository.class);
        InferenceNodeRepository nodes = mock(InferenceNodeRepository.class);
        RuntimeTrustAttestationRepository trustAttestations = mock(RuntimeTrustAttestationRepository.class);
        InferenceRuntimeClient runtimeClient = mock(InferenceRuntimeClient.class);
        OpenAiRuntimeClient openAiClient = mock(OpenAiRuntimeClient.class);

        UUID orgId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID depId = UUID.randomUUID();

        InferenceNode node = mock(InferenceNode.class);
        when(node.getOrganizationId()).thenReturn(orgId);
        when(nodes.findById(nodeId)).thenReturn(Optional.of(node));
        RuntimeEndpoint ep = mock(RuntimeEndpoint.class);
        when(ep.getId()).thenReturn(endpointId);
        when(ep.getNodeId()).thenReturn(nodeId);
        when(ep.isEnabled()).thenReturn(true);
        when(ep.getBaseUrl()).thenReturn("http://127.0.0.1:11434/v1");
        when(endpoints.findById(endpointId)).thenReturn(Optional.of(ep));
        RuntimeTrustAttestation att = mock(RuntimeTrustAttestation.class);
        when(att.getClassification()).thenReturn(RuntimeTrustAttestation.Classification.INTERNAL);
        when(att.validFor(org.mockito.ArgumentMatchers.eq("http://127.0.0.1:11434/v1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(trustAttestations.findById(endpointId)).thenReturn(Optional.of(att));
        ModelDeployment dep = mock(ModelDeployment.class);
        when(dep.isEnabled()).thenReturn(true);
        when(dep.isArchived()).thenReturn(false);
        when(dep.isExternal()).thenReturn(false);
        when(dep.getRuntimeEndpointId()).thenReturn(endpointId);
        when(dep.getProviderModelId()).thenReturn("local-masker");
        when(dep.getDisplayName()).thenReturn("Local Masker");
        when(deployments.findById(depId)).thenReturn(Optional.of(dep));

        ObjectNode maskerBody = (ObjectNode) objectMapper.readTree(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"replacements\\\":[{\\\"original\\\":\\\"user\\\",\\\"surrogate\\\":\\\"system\\\"},{\\\"original\\\":\\\"테헤란로\\\",\\\"surrogate\\\":\\\"010-0000-0000\\\"}]}\"}}]}");
        when(runtimeClient.chatCompletion(org.mockito.ArgumentMatchers.eq(ep), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.aiconnect.llmgateway.runtime.RuntimeResult(200, maskerBody));

        DataProtectionRedactor redactor = new DataProtectionRedactor(
                objectMapper, deployments, endpoints, providers, nodes, trustAttestations, runtimeClient, openAiClient);
        LlmService service = new LlmService(orgId, "svc-key", "Svc", FailoverPolicy.COMPATIBLE,
                RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
        service.configureGuardrailMasker(depId, false, true);

        ObjectNode request = (ObjectNode) objectMapper.readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"010-1234-5678 user 테헤란로\"}]}]}");
        DataProtectionRedactor.RedactionVault vault = redactor.redactRequest(request, balancedPolicy, service, true, false);

        assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(vault.restoreText("010-0000-0000")).isEqualTo("010-1234-5678");
        assertThat(request.path("messages").get(0).path("content").get(0).path("text").asText())
                .doesNotContain("테헤란로");

        // Revoked INTERNAL runtime attestation must be rejected
        when(att.validFor(org.mockito.ArgumentMatchers.eq("http://127.0.0.1:11434/v1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        assertThatThrownBy(() -> redactor.assertTrustedMaskerDeployment(orgId, depId))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("UNTRUSTED_MASKER_DEPLOYMENT");
    }

    @Test
    void streamingRestorerIsolatesMultiChoiceBuffersAndRejectsOversizedLines() throws Exception {
        DataProtectionRedactor redactor = new DataProtectionRedactor(objectMapper);
        ObjectNode request = (ObjectNode) objectMapper.readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"홍길동 965034-3231111\"}]}");
        DataProtectionRedactor.RedactionVault vault = redactor.redactRequest(request, balancedPolicy, null, false);

        String ssePayload = ""
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"김\"}},{\"index\":1,\"delta\":{\"content\":\"초이스1 김\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"가명 0\"},\"finish_reason\":\"stop\"},{\"index\":1,\"delta\":{\"content\":\"가명 1\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";

        try (SseRedactionRestoringInputStream stream = new SseRedactionRestoringInputStream(
                new ByteArrayInputStream(ssePayload.getBytes(StandardCharsets.UTF_8)), objectMapper, vault)) {
            String out = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder c0 = new StringBuilder();
            StringBuilder c1 = new StringBuilder();
            for (String line : out.split("\n")) {
                if (!line.startsWith("data:") || line.contains("[DONE]")) continue;
                JsonNode chunk = objectMapper.readTree(line.substring(5).trim());
                for (JsonNode ch : chunk.path("choices")) {
                    if (ch.path("index").asInt() == 0) c0.append(ch.path("delta").path("content").asText(""));
                    if (ch.path("index").asInt() == 1) c1.append(ch.path("delta").path("content").asText(""));
                }
            }
            assertThat(c0.toString()).isEqualTo("홍길동 0");
            assertThat(c1.toString()).isEqualTo("초이스1 홍길동 1");
        }

        byte[] endless = "data: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".getBytes(StandardCharsets.UTF_8);
        try (SseRedactionRestoringInputStream bounded = new SseRedactionRestoringInputStream(
                new ByteArrayInputStream(endless), objectMapper, vault, 16)) {
            assertThatThrownBy(bounded::readAllBytes).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void responseCacheIsolatesProjectsBoundariesDeploymentsAndTargetPrompts() throws Exception {
        ResponseCacheService cache = new ResponseCacheService(objectMapper);
        UUID orgId = UUID.randomUUID();
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        UUID dep1 = UUID.randomUUID();
        UUID dep2 = UUID.randomUUID();
        LlmService service = new LlmService(orgId, "svc-key", "Svc", FailoverPolicy.COMPATIBLE,
                RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "id", UUID.randomUUID());
        DataProtectionDecision decision = new DataProtectionDecision(
                balancedPolicy, new DataProtectionScanResult(java.util.EnumSet.noneOf(DataClassification.class), java.util.Map.of()),
                DataProtectionAction.ALLOW, true, true, List.of());
        ObjectNode req = (ObjectNode) objectMapper.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");

        String keyA = cache.cacheKey(orgId, projectA, service, decision, dep1, "target-A", req);
        String keyB = cache.cacheKey(orgId, projectB, service, decision, dep1, "target-A", req);
        String keyDep2 = cache.cacheKey(orgId, projectA, service, decision, dep2, "target-A", req);
        String keyPromptB = cache.cacheKey(orgId, projectA, service, decision, dep1, "target-B", req);
        assertThat(java.util.Set.of(keyA, keyB, keyDep2, keyPromptB)).hasSize(4);
    }
}
