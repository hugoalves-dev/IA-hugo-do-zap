package dev.hugo.watcher.notification;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.OutboxAction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ReportServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private ReportService reports;
    private final LocalDate day = LocalDate.of(2026, 10, 27);
    private final Instant now = Instant.parse("2026-10-27T15:00:00Z");

    @BeforeEach void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("delete from outbox_actions");
        jdbc.update("delete from ai_budget_reservations");
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batch_messages");
        jdbc.update("delete from simulation_batches");
        reports = new ReportService(jdbc, new DataSourceTransactionManager(source),
                new NotificationOutboxRepository(jdbc, new DataSourceTransactionManager(source)),
                Clock.fixed(now, ZoneId.of("UTC")));
    }

    @Test void dailySummaryUsesOnlyAggregateMetadataAndIsIdempotent() {
        var batch = java.util.UUID.randomUUID();
        Instant at = Instant.parse("2026-10-27T12:00:00Z");
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,?,'COMPLETED',?,?,?,?,?,?)",
                batch, "15551234567@s.whatsapp.net", "", Timestamp.from(at), Timestamp.from(at), Timestamp.from(at), Timestamp.from(at), Timestamp.from(at.plusSeconds(60)), Timestamp.from(at.plusSeconds(86400)));
        jdbc.update("insert into simulation_batch_messages(batch_id,chat_jid,participant_jid,external_id,received_at,payload) values (?,?,?,?,?,?::jsonb)",
                batch, "15551234567@s.whatsapp.net", "", "synthetic", Timestamp.from(at), "{\"text\":\"SENSITIVE_RAW_TEXT_DO_NOT_COPY\"}");
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at) values (?,true,false,'test',?,?)",
                batch, Timestamp.from(at), Timestamp.from(at.plusSeconds(86400)));
        jdbc.update("insert into ai_budget_reservations(reservation_id,estimated_micros,reserved_micros,actual_micros,reservation_day,reservation_month,status,created_at,updated_at) values (?,100,75,75,?,'2026-10-01','SETTLED',?,?)",
                java.util.UUID.randomUUID(), day, Timestamp.from(at), Timestamp.from(at));

        OutboxAction action = reports.schedule(day);
        OutboxAction duplicate = reports.schedule(day);

        assertEquals(action.id(), duplicate.id());
        assertEquals("simulation_daily_summary", action.actionType());
        assertFalse(action.payload().contains("SENSITIVE_RAW_TEXT_DO_NOT_COPY"));
        assertFalse(action.payload().contains("15551234567"));
        assertTrue(action.payload().contains("decisionCount"));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_actions where action_type='simulation_daily_summary'", Integer.class));
    }
}
