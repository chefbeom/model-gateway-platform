package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.retention.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_retention;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class RequestContentRetentionIntegrationTest {
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired LlmServiceRepository services;
    @Autowired LlmRequestRepository requests;
    @Autowired RequestContentRepository contents;
    @Autowired RequestContentService retention;

    @Test
    void retainsFullContentOnlyWhenExplicitlyEnabled() {
        Organization organization = organizations.save(new Organization("Retention Org " + UUID.randomUUID()));
        Project project = projects.save(new Project(organization.getId(), "secure-project"));
        ApiKey key = apiKeys.save(new ApiKey(project.getId(), "test", "sk_test_" + UUID.randomUUID(), "0".repeat(64), null));
        LlmService service = services.save(new LlmService(organization.getId(), "retention-service-" + UUID.randomUUID(), "Retention", FailoverPolicy.STRICT, false, "[]", java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO));
        LlmRequest metadataRequest = requests.save(new LlmRequest("metadata-" + UUID.randomUUID(), project.getId(), key.getId(), service, false));

        retention.capture(metadataRequest.getRequestId(), "secret request", "secret response");
        assertThat(contents.findById(metadataRequest.getId())).isEmpty();

        retention.setPolicy(project.getId(), ContentRetentionMode.FULL_ENCRYPTED);
        LlmRequest retained = requests.save(new LlmRequest("retained-" + UUID.randomUUID(), project.getId(), key.getId(), service, false));
        retention.capture(retained.getRequestId(), "secret request", "secret response");

        RequestContent encrypted = contents.findById(retained.getId()).orElseThrow();
        assertThat(encrypted.getEncryptedRequest()).doesNotContain("secret request");
        assertThat(retention.read(project.getId(), retained.getRequestId()).request()).isEqualTo("secret request");
        assertThat(retention.read(project.getId(), retained.getRequestId()).response()).isEqualTo("secret response");
    }
}
