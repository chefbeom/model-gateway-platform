package com.aiconnect.llmgateway.config;

import com.aiconnect.llmgateway.identity.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecretValidatorTest {
    @Test
    void rejectsDevelopmentDefaultsOutsideTestProfiles() {
        GatewayProperties gateway = new GatewayProperties("change-me-before-production", "change-me-before-production",
                "development-only-change-me-before-production", 30000, 3000, 120000);
        AuthProperties auth = new AuthProperties("development-only-auth-signing-key-change-before-production",
                "development-only-refresh-pepper-change-before-production", 900, 3600);

        assertThatThrownBy(() -> new ProductionSecretValidator(gateway, auth, new MockEnvironment(), false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_API_TOKEN");
    }

    @Test
    void rejectsDocumentedExamplePlaceholders() {
        GatewayProperties gateway = new GatewayProperties(
                "replace-with-a-long-random-break-glass-admin-token",
                "replace-with-a-long-random-api-key-pepper",
                "replace-with-a-long-random-encryption-key",
                30000, 3000, 120000);
        AuthProperties auth = new AuthProperties(
                "replace-with-a-long-random-access-token-signing-key",
                "replace-with-a-long-random-refresh-token-pepper",
                900, 3600);

        assertThatThrownBy(() -> new ProductionSecretValidator(gateway, auth, new MockEnvironment(), false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_API_TOKEN");
    }

    @Test
    void allowsExplicitLongSecrets() {
        GatewayProperties gateway = new GatewayProperties("a".repeat(32), "b".repeat(32), "c".repeat(32), 30000, 3000, 120000);
        AuthProperties auth = new AuthProperties("d".repeat(32), "e".repeat(32), 900, 3600);

        assertThatCode(() -> new ProductionSecretValidator(gateway, auth, new MockEnvironment(), false).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
