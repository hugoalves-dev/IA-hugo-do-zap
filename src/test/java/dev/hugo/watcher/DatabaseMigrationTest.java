package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test void upgradesV12WithoutChangingScopeOrTelegramSettingsAndSeedsLegacyProfiles() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("upgrade_policy").defaultSchema("upgrade_policy")
                .target("12").load().migrate();
        jdbc.update("update upgrade_policy.archive_monitor_settings set mode='ARCHIVED_ONLY' where singleton_id=1");
        var telegramNextAllowed = java.sql.Timestamp.from(java.time.Instant.parse("2026-10-27T15:00:00Z"));
        jdbc.update("update upgrade_policy.telegram_delivery_rate_limit set next_allowed_at=? where singleton_id=1", telegramNextAllowed);

        Flyway.configure().dataSource(source).schemas("upgrade_policy").defaultSchema("upgrade_policy")
                .load().migrate();

        assertEquals("ARCHIVED_ONLY", jdbc.queryForObject(
                "select mode from upgrade_policy.archive_monitor_settings where singleton_id=1", String.class));
        assertEquals(telegramNextAllowed, jdbc.queryForObject(
                "select next_allowed_at from upgrade_policy.telegram_delivery_rate_limit where singleton_id=1", java.sql.Timestamp.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from upgrade_policy.policy_profiles", Integer.class));
        assertEquals("WORK_WINDOW", jdbc.queryForObject(
                "select fields->>'mode' from upgrade_policy.policy_profiles where profile_name='WORK_ONLY'", String.class));
        assertEquals("ANYTIME", jdbc.queryForObject(
                "select fields->>'mode' from upgrade_policy.policy_profiles where profile_name='ANYTIME'", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from upgrade_policy.holiday_calendar", Integer.class));
    }

    @Test void holidayValidationRequiresRecordedManualAttestation() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("holiday_attestation").defaultSchema("holiday_attestation")
                .target("13").load().migrate();
        jdbc.update("""
                insert into holiday_attestation.holiday_calendar(local_date,jurisdiction,name,source,calendar_year,validation_status)
                values ('2026-10-27','LINS','legacy fixture','https://official.example/2026',2026,'VALIDATED')
                """);
        Flyway.configure().dataSource(source).schemas("holiday_attestation").defaultSchema("holiday_attestation")
                .load().migrate();
        assertEquals("UNKNOWN", jdbc.queryForObject("""
                select validation_status from holiday_attestation.holiday_calendar where name='legacy fixture'
                """, String.class));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into holiday_attestation.holiday_calendar(local_date,jurisdiction,name,source,calendar_year,validation_status)
                values ('2026-10-27','LINS','unattested fixture','https://official.example/2026',2026,'VALIDATED')
                """));

        jdbc.update("""
                insert into holiday_attestation.holiday_calendar(local_date,jurisdiction,name,source,calendar_year,
                    validation_status,validated_by,validated_at)
                values ('2026-10-27','LINS','fixture','https://official.example/2026',2026,'VALIDATED',?,?)
                """, "fixture-maintainer", java.sql.Timestamp.from(java.time.Instant.parse("2026-10-27T12:00:00Z")));
        assertEquals("fixture-maintainer", jdbc.queryForObject(
                "select validated_by from holiday_attestation.holiday_calendar where name='fixture'", String.class));
    }
}
