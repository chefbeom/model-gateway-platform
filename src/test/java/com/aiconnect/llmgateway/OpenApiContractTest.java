package com.aiconnect.llmgateway;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void parsesAndIncludesCoreDataControlAndFailoverPolicyContracts() throws Exception {
        try (InputStream input = Files.newInputStream(Path.of("docs", "openapi.yaml"))) {
            Map<String, Object> contract = new Yaml().load(input);
            assertThat(contract.get("openapi")).isEqualTo("3.1.0");
            Map<String, Object> paths = (Map<String, Object>) contract.get("paths");
            assertThat(paths).containsKeys(
                    "/v1/models",
                    "/v1/chat/completions",
                    "/api/auth/login",
                    "/api/admin/runtime-endpoints",
                    "/api/admin/model-deployments",
                    "/api/admin/services/{serviceId}/targets",
                    "/api/admin/organizations/{organizationId}/requests",
                    "/api/me/usage");
            assertThat(paths).hasSizeGreaterThanOrEqualTo(20);

            Map<String, Object> components = (Map<String, Object>) contract.get("components");
            Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
            Map<String, Object> logicalService = (Map<String, Object>) schemas.get("CreateLogicalService");
            Map<String, Object> serviceProperties = (Map<String, Object>) logicalService.get("properties");
            Map<String, Object> retryPolicy = (Map<String, Object>) serviceProperties.get("retryPolicy");
            assertThat((List<String>) retryPolicy.get("enum")).containsExactly("SAFE", "AGGRESSIVE");

            Map<String, Object> deployment = (Map<String, Object>) schemas.get("CreateModelDeployment");
            assertThat((Map<String, Object>) deployment.get("properties")).containsKey("compatibilityKey");
        }
    }
}
