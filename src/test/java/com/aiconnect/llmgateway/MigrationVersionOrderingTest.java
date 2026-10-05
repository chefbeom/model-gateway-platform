package com.aiconnect.llmgateway;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationVersionOrderingTest {
    @Test
    void newAdminFeaturesMigrateAfterPreviouslyReleasedPlaygroundMetadata() throws Exception {
        Map<String, Integer> versions = new HashMap<>();
        Set<Integer> uniqueVersions = new HashSet<>();
        var migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        for (var resource : migrations) {
            String filename = resource.getFilename();
            assertNotNull(filename);
            String[] parts = filename.split("__", 2);
            int version = Integer.parseInt(parts[0].substring(1));
            assertTrue(uniqueVersions.add(version), "Duplicate migration version: " + version);
            versions.put(parts[1], version);
        }
        Integer deployedBaseline = versions.get("playground_request_metadata.sql");
        assertNotNull(deployedBaseline);
        for (String feature : new String[]{"playground_conversation_history.sql", "model_feature_support.sql"}) {
            Integer version = versions.get(feature);
            assertNotNull(version, "Missing migration: " + feature);
            assertTrue(version > deployedBaseline,
                    "New migration would be ignored on an existing production schema: " + feature);
        }
    }
}
