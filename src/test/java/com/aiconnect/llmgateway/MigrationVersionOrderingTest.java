package com.aiconnect.llmgateway;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationVersionOrderingTest {
    @Test
    void newAdminFeaturesMigrateAfterPreviouslyReleasedPlaygroundMetadata() throws Exception {
        Map<String, Integer> versions = new HashMap<>();
        Set<Integer> uniqueVersions = new HashSet<>();
        List<Integer> discoveredVersions = new ArrayList<>();
        var migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        for (var resource : migrations) {
            String filename = resource.getFilename();
            assertNotNull(filename);
            String[] parts = filename.split("__", 2);
            int version = Integer.parseInt(parts[0].substring(1));
            assertTrue(uniqueVersions.add(version), "Duplicate migration version: " + version);
            versions.put(parts[1], version);
            discoveredVersions.add(version);
        }
        Collections.sort(discoveredVersions);
        for (int i = 1; i < discoveredVersions.size(); i++) {
            assertTrue(discoveredVersions.get(i) > discoveredVersions.get(i - 1),
                    "Migration versions must be strictly increasing");
        }
        assertEquals(uniqueVersions.size(), discoveredVersions.size());

        Integer deployedBaseline = versions.get("playground_request_metadata.sql");
        assertNotNull(deployedBaseline);
        for (String feature : new String[]{
                "playground_conversation_history.sql",
                "model_feature_support.sql",
                "beta_invitation_service.sql",
                "account_tiers_invitations_and_totp.sql",
                "project_membership.sql",
                "runtime_trust_attestation.sql",
                "project_runtime_approval.sql",
                "connection_registration_request.sql",
                "project_scope_enforcement.sql",
                "governance_policy_intersection_and_delegation.sql"
        }) {
            Integer version = versions.get(feature);
            assertNotNull(version, "Missing migration: " + feature);
            assertTrue(version > deployedBaseline,
                    "New migration would be ignored on an existing production schema: " + feature);
        }
        Integer previousRelease = versions.get("archive_external_models.sql");
        assertNotNull(previousRelease, "Missing migration: archive_external_models.sql");
        assertTrue(versions.get("beta_invitation_service.sql") > previousRelease,
                "Beta invitation migration must order after archive_external_models.sql");
        assertTrue(versions.get("account_tiers_invitations_and_totp.sql") > versions.get("beta_invitation_service.sql"),
                "Account tiers and TOTP migration must order after beta_invitation_service.sql");
        assertTrue(versions.get("project_membership.sql") > versions.get("account_tiers_invitations_and_totp.sql"));
        assertTrue(versions.get("runtime_trust_attestation.sql") > versions.get("project_membership.sql"));
        assertTrue(versions.get("project_runtime_approval.sql") > versions.get("runtime_trust_attestation.sql"));
        assertTrue(versions.get("connection_registration_request.sql") > versions.get("project_runtime_approval.sql"));
        assertTrue(versions.get("project_scope_enforcement.sql") > versions.get("connection_registration_request.sql"));
        Integer v49 = versions.get("provider_cap_and_quota_ceilings.sql");
        if (v49 != null) {
            assertTrue(v49 > versions.get("project_scope_enforcement.sql"),
                    "V49 provider cap migration must order after V48");
            assertTrue(versions.get("governance_policy_intersection_and_delegation.sql") > v49,
                    "V50 governance migration must order after V49");
        } else {
            assertTrue(versions.get("governance_policy_intersection_and_delegation.sql") > versions.get("project_scope_enforcement.sql"),
                    "V50 governance migration must order after V48");
        }
    }
}
