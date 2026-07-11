package com.aiconnect.llmgateway;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiObservationContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void includesConsumerHistoryAndIncidentDeliveryContracts() throws Exception {
        try (InputStream input = Files.newInputStream(Path.of("docs", "openapi.yaml"))) {
            Map<String, Object> contract = new Yaml().load(input);
            Map<String, Object> paths = (Map<String, Object>) contract.get("paths");

            assertThat(paths).containsKeys(
                    "/api/me/requests",
                    "/api/admin/organizations/{organizationId}/incidents");

            Map<String, Object> incidentPath = (Map<String, Object>) paths.get(
                    "/api/admin/organizations/{organizationId}/incidents");
            Map<String, Object> incidentGet = (Map<String, Object>) incidentPath.get("get");
            assertThat(incidentGet).containsKeys("security", "parameters", "responses");
        }
    }
}
