package com.aiconnect.llmgateway;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            Flyway upgrade = Flyway.configure().dataSource(url, user, password).load();
            assertEquals(2, upgrade.migrate().migrationsExecuted);
            assertEquals("38", upgrade.info().current().getVersion().getVersion());
            assertEquals(organizations, count(connection, "SELECT COUNT(*) FROM organization"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name='playground_conversation'"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name='model_deployment' "
                    + "AND column_name='feature_support_json'"));
        }
    }

    private static int count(Connection connection, String query) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(query)) {
            result.next();
            return result.getInt(1);
        }
    }
}
