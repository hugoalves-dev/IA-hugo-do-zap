package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void upgradesExistingPendingRowsWithGenerationWithoutChangingPayload() throws Exception {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_generation").defaultSchema("upgrade_generation")
                .target("9").load().migrate();
        jdbc.update("insert into upgrade_generation.pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values (?,?::jsonb,now(),now(),now()+interval '1 day')",
                "fixture-upgrade@s.whatsapp.net", "{\"messageCount\":2}");
        Flyway.configure().dataSource(source).schemas("upgrade_generation").defaultSchema("upgrade_generation")
                .load().migrate();
        assertNotNull(jdbc.queryForObject("select generation from upgrade_generation.pending_notification", java.util.UUID.class));
        assertEquals("2", jdbc.queryForObject("select payload->>'messageCount' from upgrade_generation.pending_notification", String.class));
    }

    @Test void flywayIsRepeatableAndArchivedRemainsTriState() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load();
        flyway.migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("insert into chat_snapshot (chat_jid, archived) values (?, null)")) {
            statement.setString(1, "120363000000000000@g.us");
            statement.executeUpdate();
        }

        flyway.migrate();

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("select archived from chat_snapshot where chat_jid = '120363000000000000@g.us'")) {
            assertEquals(true, result.next());
            assertNull(result.getObject("archived"));
            assertEquals(false, result.next());
        }
    }
}
