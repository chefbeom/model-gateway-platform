package com.aiconnect.llmgateway.dataprotection;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.DataProtectionPolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataProtectionPolicyServiceTest {
    private final DataProtectionPolicyRepository policies = mock(DataProtectionPolicyRepository.class);
    private final DataProtectionPolicyService service = new DataProtectionPolicyService(
            policies, mock(ApiKeyRepository.class), new DataProtectionScanner(new ObjectMapper()), new ObjectMapper());

    @Test
    void mergesScopesUsingTheMostRestrictiveModeActionAndFailoverBoundary() {
        UUID organizationId = UUID.randomUUID();
        DataProtectionPolicy organization = new DataProtectionPolicy(
                organizationId, DataProtectionScopeType.ORGANIZATION, organizationId,
                DataProtectionMode.MONITOR, DataProtectionLevel.BALANCED, DataProtectionAction.ALLOW,
                true, true, true, false, false, true, "[]");
        DataProtectionPolicy project = new DataProtectionPolicy(
                organizationId, DataProtectionScopeType.PROJECT, UUID.randomUUID(),
                DataProtectionMode.ENFORCE, DataProtectionLevel.STRICT, DataProtectionAction.LOCAL_ONLY,
                false, true, false, true, true, true, "[\"internal-[0-9]+\"]");

        EffectiveDataProtectionPolicy result = service.merge(java.util.List.of(organization, project));

        assertThat(result.mode()).isEqualTo(DataProtectionMode.ENFORCE);
        assertThat(result.level()).isEqualTo(DataProtectionLevel.STRICT);
        assertThat(result.externalAction()).isEqualTo(DataProtectionAction.LOCAL_ONLY);
        assertThat(result.allowExternalFailover()).isFalse();
        assertThat(result.detectPii()).isTrue();
        assertThat(result.detectFinancial()).isTrue();
        assertThat(result.customPatterns()).containsExactly("internal-[0-9]+");
        assertThat(result.appliedScopes()).containsExactly("ORGANIZATION", "PROJECT");
    }

    @Test
    void enforcePolicyBlocksSensitiveRequestAndReportsOnlyClassificationNames() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        DataProtectionPolicy policy = new DataProtectionPolicy(
                organizationId, DataProtectionScopeType.PROJECT, projectId,
                DataProtectionMode.ENFORCE, DataProtectionLevel.STRICT, DataProtectionAction.BLOCK,
                false, false, true, false, false, false, "[]");
        when(policies.findByScopeTypeAndOrganizationIdIsNullAndScopeIdIsNull(DataProtectionScopeType.PLATFORM))
                .thenReturn(Optional.empty());
        when(policies.findByScopeTypeAndOrganizationIdAndScopeId(DataProtectionScopeType.ORGANIZATION, organizationId, organizationId))
                .thenReturn(Optional.empty());
        when(policies.findByScopeTypeAndOrganizationIdAndScopeId(DataProtectionScopeType.PROJECT, organizationId, projectId))
                .thenReturn(Optional.of(policy));
        when(policies.findByScopeTypeAndOrganizationIdAndScopeId(DataProtectionScopeType.SERVICE, organizationId, serviceId))
                .thenReturn(Optional.empty());
        when(policies.findByScopeTypeAndOrganizationIdAndScopeId(DataProtectionScopeType.API_KEY, organizationId, keyId))
                .thenReturn(Optional.empty());

        Project project = new Project(organizationId, "privacy-test");
        ReflectionTestUtils.setField(project, "id", projectId);
        ApiKey key = new ApiKey(projectId, "test", "sk_test", "hash", null);
        ReflectionTestUtils.setField(key, "id", keyId);
        LlmService logicalService = new LlmService(organizationId, "text-pro", "Text Pro",
                FailoverPolicy.COMPATIBLE, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO);
        ReflectionTestUtils.setField(logicalService, "id", serviceId);
        var request = new ObjectMapper().readTree(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"send jane@example.com\"}]}");

        DataProtectionDecision decision = service.inspect(project, key, logicalService, request, null);

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.externalAllowed()).isFalse();
        assertThat(decision.externalFailoverAllowed()).isFalse();
        assertThat(decision.scan().classifications()).containsExactly(DataClassification.PII);
        assertThat(decision.classificationSummary()).isEqualTo("PII");
        assertThat(decision.reasonCodes()).contains("DATA_DETECTED_PII", "DATA_PROTECTION_EXTERNAL_BLOCKED");
    }
}
