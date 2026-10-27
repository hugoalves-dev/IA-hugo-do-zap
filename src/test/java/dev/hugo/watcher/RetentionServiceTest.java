package dev.hugo.watcher;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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

    @Test void scrubsExpiredInboxPayloadAndExpiresEveryJobWhilePreservingDedupMetadata() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from processing_jobs");
        jdbc.update("delete from inbox_events");
        jdbc.update("delete from pending_notification");
        Instant cutoff = Instant.now().plus(Duration.ofDays(8));
        for (String status : java.util.List.of("READY", "PENDING", "PROCESSING")) {
            String eventId = "retention-" + status.toLowerCase();
            Long inboxId = jdbc.queryForObject("""
                    insert into inbox_events(source,event_type,external_id,payload)
                    values ('evolution','messages.upsert',?, '{\"data\":\"sensitive\"}'::jsonb) returning id
                    """, Long.class, eventId);
            jdbc.update("""
                    insert into processing_jobs(inbox_event_id,status,attempts,lease_token,lease_expires_at)
                    values (?, ?, 2, case when ?='PROCESSING' then gen_random_uuid() else null end,
                            case when ?='PROCESSING' then now()+interval '2 minutes' else null end)
                    """, inboxId, status, status, status);
        }

        new RetentionService(jdbc).purge(cutoff);

        assertEquals(3, jdbc.queryForObject("select count(*) from inbox_events where payload='{}'::jsonb", Integer.class));
        assertEquals(3, jdbc.queryForObject("select count(*) from processing_jobs where status='EXPIRED' and lease_token is null and lease_expires_at is null", Integer.class));
        assertEquals(3, jdbc.queryForObject("select count(*) from inbox_events where source='evolution' and event_type='messages.upsert' and external_id like 'retention-%'", Integer.class));
        var replay = new JdbcInboxRepository(jdbc).accept(new EvolutionEvent("evolution", "watcher-test",
                "messages.upsert", "retention-ready", Map.of("data", "replayed")));
        assertEquals(Acceptance.DUPLICATE, replay);
        assertEquals(3, jdbc.queryForObject("select count(*) from processing_jobs", Integer.class));
    }
}
