package com.aiconnect.llmgateway.service;

import com.aiconnect.llmgateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCipherTest {
    @Test
    void encryptsRuntimeTokenWithoutKeepingPlaintext() {
        SecretCipher cipher = new SecretCipher(new GatewayProperties("admin", "pepper", "unit-test-encryption-key", 30_000, 1_000, 5_000));

        String encrypted = cipher.encrypt("lmstudio-secret-token");

        assertThat(encrypted).doesNotContain("lmstudio-secret-token");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("lmstudio-secret-token");
    }
}
