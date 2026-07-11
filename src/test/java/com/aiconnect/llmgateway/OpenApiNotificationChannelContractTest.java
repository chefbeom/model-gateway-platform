package com.aiconnect.llmgateway;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiNotificationChannelContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void documentsChannelListingAndStateChanges() throws Exception {
        try (InputStream input = Files.newInputStream(Path.of("docs", "openapi.yaml"))) {
            Map<String, Object> contract = new Yaml().load(input);
            Map<String, Object> paths = (Map<String, Object>) contract.get("paths");

            Map<String, Object> collection = (Map<String, Object>) paths.get(
                    "/api/admin/organizations/{organizationId}/notification-channels");
            assertThat(collection).containsKeys("get", "post");

            Map<String, Object> item = (Map<String, Object>) paths.get(
                    "/api/admin/organizations/{organizationId}/notification-channels/{channelId}");
            assertThat(item).containsKey("patch");
        }
    }
}
