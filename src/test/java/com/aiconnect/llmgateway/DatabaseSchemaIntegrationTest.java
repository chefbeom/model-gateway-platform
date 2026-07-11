package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
class DatabaseSchemaIntegrationTest {
    @Autowired OrganizationRepository organizations;

    @Test
    void flywayMigrationsAndJpaMappingsLoadTogether() {
        assertThat(organizations.count()).isZero();
    }
}
