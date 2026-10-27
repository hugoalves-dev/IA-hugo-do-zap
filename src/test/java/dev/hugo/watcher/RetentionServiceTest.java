package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class RetentionServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void purgesExpiredSnippetsAndKeepsRetainedDataWithinSevenDays() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from pending_notification");
        Instant now = Instant.parse("2026-10-27T12:00:00Z");
        Instant oldUpdated = now.minusSeconds(8 * 86_400L);
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values ('expired','{}'::jsonb,?,?,?)",
                Timestamp.from(oldUpdated), Timestamp.from(oldUpdated), Timestamp.from(oldUpdated.plusSeconds(7 * 86_400L)));
        Instant recentUpdated = now.minusSeconds(86_400L);
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values ('recent',?::jsonb,?,?,?)",
                "{\"preview\":\"recent\",\"previewExpiresAt\":\"" + recentUpdated.plusSeconds(7 * 86_400L) + "\"}",
                Timestamp.from(recentUpdated), Timestamp.from(recentUpdated), Timestamp.from(recentUpdated.plusSeconds(7 * 86_400L)));

        assertEquals(1, new RetentionService(jdbc).purge(now));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification where chat_jid='expired'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where chat_jid='recent' and expires_at <= updated_at + interval '7 days'", Integer.class));
    }
}
