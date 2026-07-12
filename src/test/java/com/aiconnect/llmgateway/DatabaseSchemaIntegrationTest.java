package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that JPA can create the complete isolated integration schema.
 *
 * Production Flyway migrations intentionally target MariaDB. H2 cannot run
 * every MariaDB-specific ALTER TABLE statement, so this test validates entity
 * mappings. A real MariaDB startup and migration check remains part of release validation.
 */
@SpringBootTest
@ActiveProfiles("integration")
class DatabaseSchemaIntegrationTest {
    @Autowired OrganizationRepository organizations;

    @Test
    void jpaMappingsCreateAnUsableIntegrationSchema() {
        assertThat(organizations.count()).isZero();
    }
}
