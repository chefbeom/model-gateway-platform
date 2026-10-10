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
            int projects = count(connection, "SELECT COUNT(*) FROM project");
            Flyway upgrade = Flyway.configure().dataSource(url, user, password).load();
            // Derive the upgrade contract from the resolved migrations so adding a
            // future migration does not leave a stale count or final version here.
            int pendingMigrations = upgrade.info().pending().length;
            var latestVersion = Arrays.stream(upgrade.info().all())
                    .map(migration -> migration.getVersion())
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElseThrow();
            assertTrue(pendingMigrations >= 3, "V37, V38 and V39 must upgrade the V36 schema");
            assertEquals(pendingMigrations, upgrade.migrate().migrationsExecuted);
            assertEquals(latestVersion, upgrade.info().current().getVersion());
            assertEquals(organizations, count(connection, "SELECT COUNT(*) FROM organization"));
            assertEquals(projects, count(connection, "SELECT COUNT(*) FROM project"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='playground_conversation'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='model_deployment' "
                    + "AND column_name='feature_support_json'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='project' "
                    + "AND column_name='external_ai_blocked' AND is_nullable='NO'"));
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

    private static int count(Connection connection, String query) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            result.next();
            return result.getInt(1);
        }
    }
}
