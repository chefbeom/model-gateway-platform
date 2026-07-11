package com.aiconnect.llmgateway;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiOperationsContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void documentsResourceDiscoveryOverviewAndEndpointConflictContracts() throws Exception {
        try (InputStream input = Files.newInputStream(Path.of("docs", "openapi.yaml"))) {
            Map<String, Object> contract = new Yaml().load(input);
            Map<String, Object> paths = (Map<String, Object>) contract.get("paths");
            assertThat(paths).containsKeys(
                    "/api/admin/overview",
                    "/api/admin/organizations/{organizationId}/overview",
                    "/api/admin/organizations/{organizationId}/projects");

            Map<String, Object> organizations = (Map<String, Object>) paths.get("/api/admin/organizations");
            assertThat(organizations).containsKeys("get", "post");

            Map<String, Object> endpoints = (Map<String, Object>) paths.get("/api/admin/runtime-endpoints");
            Map<String, Object> endpointPost = (Map<String, Object>) endpoints.get("post");
            Map<String, Object> responses = (Map<String, Object>) endpointPost.get("responses");
            assertThat(responses).containsKeys("200", "400", "409");
        }
    }
}
