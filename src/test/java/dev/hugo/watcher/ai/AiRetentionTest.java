package dev.hugo.watcher.ai;

import dev.hugo.watcher.RetentionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class AiRetentionTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void purgesSensitiveClassificationSnippetBySevenDaysAndKeepsDecisionMetadata() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batch_messages");
        jdbc.update("delete from simulation_batches");
        Instant created = Instant.parse("2026-10-01T12:00:00Z");
        Instant now = created.plusSeconds(8 * 86_400L);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,
                    created_at,expires_at,metadata_expires_at) values (?,?,?,'COMPLETED',?,?,?,?,?,?)
                """, id, "private-chat", "", Timestamp.from(created), Timestamp.from(created), Timestamp.from(created),
                Timestamp.from(created), Timestamp.from(created.plusSeconds(7 * 86_400L)), Timestamp.from(created.plusSeconds(90 * 86_400L)));
        jdbc.update("""
                insert into simulation_batch_messages(batch_id,chat_jid,participant_jid,external_id,received_at,payload)
                values (?,?,?,'snippet',?,?::jsonb)
                """, id, "private-chat", "", Timestamp.from(created), "{\"text\":\"private content\"}");
        jdbc.update("""
                insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at)
                values (?,false,false,'none',?,?)
                """, id, Timestamp.from(created), Timestamp.from(created.plusSeconds(90 * 86_400L)));

        new RetentionService(jdbc, new DataSourceTransactionManager(source)).purge(now);

        assertEquals(0, jdbc.queryForObject("select count(*) from simulation_batch_messages where batch_id=?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_decisions where batch_id=?", Integer.class, id));
        assertEquals("COMPLETED", jdbc.queryForObject("select status from simulation_batches where batch_id=?", String.class, id));
    }
}
