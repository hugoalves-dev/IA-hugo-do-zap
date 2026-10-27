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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class RetentionRegressionTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void upgradeBoundsPreexistingPendingWithoutRefreshingItsAge() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).schemas("legacy_retention").target("3").load().migrate();
        // Use qualified names because DriverManagerDataSource opens a new connection per operation.
        jdbc.update("insert into legacy_retention.pending_notification(chat_jid,payload,due_at,updated_at) values ('recent','{\"preview\":\"recent\"}',now(),now()-interval '2 days'),('expired','{\"preview\":\"old\"}',now(),now()-interval '8 days')");
        Flyway.configure().dataSource(source).schemas("legacy_retention").load().migrate();
        assertEquals(0, jdbc.queryForObject("select count(*) from legacy_retention.pending_notification where expires_at > updated_at+interval '7 days'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from legacy_retention.pending_notification where chat_jid='expired'", Integer.class));
        assertEquals(true, jdbc.queryForObject("select expires_at=updated_at+interval '7 days' and updated_at < now()-interval '1 day' from legacy_retention.pending_notification where chat_jid='recent'", Boolean.class));
    }

    @Test void scrubPreservesPreviewReplacedAfterCandidateRead() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).load().migrate();
        Instant now = Instant.parse("2026-10-27T12:00:00Z");
        String replacement = "{\"preview\":\"new preview\",\"previewExpiresAt\":\"2026-10-28T12:00:00Z\"}";
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values ('race','{\"preview\":\"expired preview\",\"previewExpiresAt\":\"2026-10-26T12:00:00Z\"}',?,?,?)",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.plusSeconds(172800)));
        boolean[] replaced = {false};
        var interleavedJdbc = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("update pending_notification set payload=payload-'preview'")) {
                    // Commit a real writer between the candidate SELECT and scrub UPDATE.
                    jdbc.update("update pending_notification set payload=?::jsonb where chat_jid='race'", replacement);
                    replaced[0] = true;
                }
                return super.update(sql, args);
            }
        };
        assertEquals(0, new SensitiveDataRetentionService(interleavedJdbc).purge(now));
        assertTrue(replaced[0], "the concurrent replacement must happen before scrub executes");
        assertEquals("new preview", jdbc.queryForObject("select payload->>'preview' from pending_notification where chat_jid='race'", String.class));
        assertEquals("2026-10-28T12:00:00Z", jdbc.queryForObject("select payload->>'previewExpiresAt' from pending_notification where chat_jid='race'", String.class));
    }
}
