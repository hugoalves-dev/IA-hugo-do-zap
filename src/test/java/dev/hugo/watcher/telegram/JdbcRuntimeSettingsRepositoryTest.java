package dev.hugo.watcher.telegram;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcRuntimeSettingsRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private JdbcRuntimeSettingsRepository repository;

    @BeforeEach void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        repository = new JdbcRuntimeSettingsRepository(jdbc, new DataSourceTransactionManager(source));
    }

    @Test void changesAndRestoresAreVersionedAndKeepAnAppendOnlyHistory() {
        Instant first = Instant.parse("2026-11-04T12:00:00Z");
        repository.update("ai.context_window_hours", "INTEGER", "12", true, 0, 1001, first);
        repository.update("ai.context_window_hours", "INTEGER", "24", false, 1, 1001, first.plusSeconds(60));

        assertEquals(2, repository.findAll().get("ai.context_window_hours").version());
        assertEquals(2, jdbc.queryForObject("select count(*) from telegram_runtime_settings_history", Integer.class));
        assertNull(jdbc.queryForObject("select previous_json from telegram_runtime_settings_history where version=1", Object.class));
        assertEquals("12", jdbc.queryForObject("select next_json::text from telegram_runtime_settings_history where version=1", String.class));
        assertEquals("1001", jdbc.queryForObject("select actor_user_id::text from telegram_runtime_settings_history where version=2", String.class));
    }

    @Test void staleVersionDoesNotAppendAnAuditRow() {
        repository.update("ai.context_window_hours", "INTEGER", "12", true, 0, 1001,
                Instant.parse("2026-11-04T12:00:00Z"));
        assertThrows(RuntimeSettingVersionConflictException.class,
                () -> repository.update("ai.context_window_hours", "INTEGER", "8", true, 0, 1002,
                        Instant.parse("2026-11-04T12:01:00Z")));
        assertEquals(1, jdbc.queryForObject("select count(*) from telegram_runtime_settings_history", Integer.class));
        assertEquals("12", repository.findAll().get("ai.context_window_hours").valueJson());
    }
}
