package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.quota.ProjectQuota;
import com.aiconnect.llmgateway.quota.ProjectQuotaRepository;
import com.aiconnect.llmgateway.quota.QuotaService;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.service.IssuedApiKey;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_token_quota;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ProjectTokenQuotaIntegrationTest {
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired LlmServiceRepository services;
    @Autowired LlmRequestRepository requests;
    @Autowired ProjectQuotaRepository quotas;
    @Autowired ApiKeyService apiKeyService;
    @Autowired QuotaService quotaService;
    @Autowired ObjectMapper objectMapper;

    @Test
    void blocksAtLimitUsingProjectScopedDatabaseTokenAggregate() throws Exception {
        Organization organization = organizations.save(new Organization("Quota Org"));
        Project project = projects.save(new Project(organization.getId(), "limited"));
        IssuedApiKey key = apiKeyService.issue(project.getId(), "quota", null);
        ApiKey apiKey = apiKeyService.authenticate("Bearer " + key.secret()).apiKey();
        LlmService service = services.save(new LlmService(organization.getId(), "quota-service", "Quota",
                FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", BigDecimal.ZERO, BigDecimal.ZERO));
        LlmRequest used = new LlmRequest(UUID.randomUUID().toString(), project.getId(), apiKey.getId(), service, false);
        used.succeed(null, 6, 4, 1, 200, 0); requests.save(used);
        quotas.save(new ProjectQuota(project.getId(), 60, 10L));

        assertThatThrownBy(() -> quotaService.check("Bearer " + key.secret(), objectMapper.readTree("{\"model\":\"quota-service\"}")))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode()).isEqualTo("TOKEN_QUOTA_EXCEEDED"));
    }
}
