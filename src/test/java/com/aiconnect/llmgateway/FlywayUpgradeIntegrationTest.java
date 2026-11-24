package com.aiconnect.llmgateway;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses only the dedicated, disposable MariaDB schema supplied by CI. */
@EnabledIfEnvironmentVariable(named = "TEST_MARIADB_URL", matches = ".+")
class FlywayUpgradeIntegrationTest {
    @Test
    void upgradesExistingVersion36WithoutOutOfOrderMode() throws Exception {
        String url = System.getenv("TEST_MARIADB_URL");
        String user = System.getenv("TEST_MARIADB_USERNAME");
        String password = System.getenv("TEST_MARIADB_PASSWORD");
        Flyway baseline = Flyway.configure().dataSource(url, user, password).target("36").load();
        baseline.migrate();
        assertEquals("36", baseline.info().current().getVersion().getVersion());
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            int organizations = count(connection, "SELECT COUNT(*) FROM organization");
            UUID existingProject = insertProject(connection);
            UUID sysAdminId = insertUser(connection, "v36-sysadmin@example.com", true);
            UUID directorId = insertUser(connection, "v36-director@example.com", false);
            UUID devId = insertUser(connection, "v36-dev@example.com", false);
            insertOrgMember(connection, directorId, "ORGANIZATION_ADMIN");
            insertOrgMember(connection, devId, "DEVELOPER");
            UUID existingRefreshTokenId = insertRefreshToken(connection, sysAdminId);
            int projects = count(connection, "SELECT COUNT(*) FROM project");
            Flyway upgrade = Flyway.configure().dataSource(url, user, password).load();
            // Derive the upgrade contract from the resolved migrations so adding a
            // future migration does not leave a stale count or final version here.
            int pendingMigrations = upgrade.info().pending().length;
            var latestVersion = Arrays.stream(upgrade.info().all())
                    .map(migration -> migration.getVersion())
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElseThrow();
            assertTrue(pendingMigrations >= 4, "V37..V43 must upgrade the V36 schema");
            assertEquals(pendingMigrations, upgrade.migrate().migrationsExecuted);
            assertEquals(latestVersion, upgrade.info().current().getVersion());
            assertEquals(organizations, count(connection, "SELECT COUNT(*) FROM organization"));
            assertEquals(projects, count(connection, "SELECT COUNT(*) FROM project"));
            for (String table : new String[]{"beta_service", "beta_invitation", "beta_member", "beta_operator"}) {
                assertEquals(0, count(connection, "SELECT COUNT(*) FROM " + table),
                        "Upgrading must not implicitly create or enable beta access: " + table);
            }
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='account_invitation'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user_totp_recovery_code'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='account_tier' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='app_user' AND column_name='totp_enabled' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='auth_refresh_token' AND column_name='mfa_verified' AND is_nullable='NO'"));
            assertEquals("SYSTEM_ADMIN", stringValue(connection, "SELECT account_tier FROM app_user WHERE id=?", sysAdminId));
            assertEquals("PROJECT_DIRECTOR", stringValue(connection, "SELECT account_tier FROM app_user WHERE id=?", directorId));
            assertEquals("GENERAL_USER", stringValue(connection, "SELECT account_tier FROM app_user WHERE id=?", devId));
            assertFalse(booleanValue(connection, "SELECT totp_enabled FROM app_user WHERE id=?", sysAdminId));
            assertFalse(booleanValue(connection, "SELECT mfa_verified FROM auth_refresh_token WHERE id=?", existingRefreshTokenId));
            assertEquals(1,count(connection,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='service_request_scheduling'"));
            assertEquals(1,count(connection,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='service_flow_layout'"));
            assertEquals(1,count(connection,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='service_target' AND column_name='system_prompt_template'"));
            assertEquals(1,count(connection,"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='request_scheduling_trace'"));
            assertEquals(1,count(connection,"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='runtime_endpoint' AND column_name='max_concurrent_requests' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='playground_conversation'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='model_deployment' "
                    + "AND column_name='feature_support_json'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='project' "
                    + "AND column_name='external_ai_blocked' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='project_membership'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='project_membership' "
                    + "AND column_name='can_issue_api_keys' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='runtime_trust_attestation'"));
            for (String metaCol : new String[]{"vendor_name", "processing_location", "retention_policy", "approval_scope"}) {
                assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='runtime_trust_attestation' "
                        + "AND column_name='" + metaCol + "'"));
            }
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='project_runtime_approval'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='connection_registration_request'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='app_user' "
                    + "AND column_name='project_scope_enforced' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='project' "
                    + "AND column_name='data_boundary_policy' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='project' "
                    + "AND column_name='max_data_boundary_policy' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='llm_service' "
                    + "AND column_name='data_boundary_policy' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='api_key' "
                    + "AND column_name='data_boundary_policy' AND is_nullable='NO'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='account_invitation' "
                    + "AND column_name='can_issue_api_keys' AND is_nullable='NO'"));
            assertEquals("EXTERNAL_ALLOWED", stringValue(connection, "SELECT data_boundary_policy FROM project WHERE id=?", existingProject));
            assertEquals("EXTERNAL_ALLOWED", stringValue(connection, "SELECT max_data_boundary_policy FROM project WHERE id=?", existingProject));
            assertFalse(booleanValue(connection, "SELECT project_scope_enforced FROM app_user WHERE id=?", devId));
            assertFalse(externalAiBlocked(connection, existingProject),
                    "Upgrading must not enable the boundary for existing projects");
            UUID newProject = insertProject(connection);
            assertFalse(externalAiBlocked(connection, newProject),
                    "Omitting the new column must retain its database default of OFF");
            try (var statement = connection.prepareStatement("UPDATE project SET external_ai_blocked=TRUE WHERE id=?")) {
                statement.setString(1, existingProject.toString());
                assertEquals(1, statement.executeUpdate());
            }
            assertEquals(0, upgrade.migrate().migrationsExecuted);
            assertTrue(externalAiBlocked(connection, existingProject),
                    "A subsequent migration run must preserve an enabled boundary");
            assertEquals(projects + 1, count(connection, "SELECT COUNT(*) FROM project"));
        }
    }

    private static UUID insertProject(Connection connection) throws Exception {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement("INSERT INTO project (id, organization_id, name) "
                + "SELECT ?, id, ? FROM organization ORDER BY id LIMIT 1")) {
            statement.setString(1, id.toString());
            statement.setString(2, "migration-test-" + id);
            assertEquals(1, statement.executeUpdate());
        }
        return id;
    }

    private static boolean externalAiBlocked(Connection connection, UUID projectId) throws Exception {
        try (var statement = connection.prepareStatement("SELECT external_ai_blocked FROM project WHERE id=?")) {
            statement.setString(1, projectId.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                boolean blocked = result.getBoolean(1);
                assertFalse(result.wasNull(), "The project boundary must never be NULL");
                return blocked;
            }
        }
    }

    private static UUID insertUser(Connection connection, String email, boolean platformAdmin) throws Exception {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement(
                "INSERT INTO app_user (id, email, password_hash, platform_admin) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, id.toString());
            statement.setString(2, email);
            statement.setString(3, "v36-hash");
            statement.setBoolean(4, platformAdmin);
            assertEquals(1, statement.executeUpdate());
        }
        return id;
    }

    private static void insertOrgMember(Connection connection, UUID userId, String role) throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO organization_member (organization_id, user_id, role) "
                        + "SELECT id, ?, ? FROM organization ORDER BY id LIMIT 1")) {
            statement.setString(1, userId.toString());
            statement.setString(2, role);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static UUID insertRefreshToken(Connection connection, UUID userId) throws Exception {
        UUID id = UUID.randomUUID();
        try (var statement = connection.prepareStatement(
                "INSERT INTO auth_refresh_token (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, DATE_ADD(NOW(), INTERVAL 1 DAY))")) {
            statement.setString(1, id.toString());
            statement.setString(2, userId.toString());
            statement.setString(3, id.toString().replace("-", "") + "0".repeat(32));
            assertEquals(1, statement.executeUpdate());
        }
        return id;
    }

    private static String stringValue(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static boolean booleanValue(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                boolean value = result.getBoolean(1);
                assertFalse(result.wasNull());
                return value;
            }
        }
    }

    private static int count(Connection connection, String query) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            result.next();
            return result.getInt(1);
        }
    }
}
