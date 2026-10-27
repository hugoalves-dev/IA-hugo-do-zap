package dev.hugo.watcher;

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
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ChatStateServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private ChatStateService chats;

    @BeforeEach void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.update("delete from pending_notification");
        jdbc.update("delete from processed_message");
        jdbc.update("delete from quarantined_events");
        jdbc.update("delete from chat_snapshot");
        chats = new ChatStateService(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test void unknownArchiveStateIsQuarantinedAndCannotBootstrapAChat() {
        var result = chats.apply(new ChatUpdate("5511999999999@s.whatsapp.net", Optional.empty(),
                Instant.parse("2026-10-26T10:00:00Z"), true, true, Optional.of("event-unknown"),
                Optional.of("Contato"), false, Map.of("archived", "unknown")));

        assertEquals(ChatStateService.ApplyResult.QUARANTINED, result);
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_snapshot", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from quarantined_events where source_event_id='event-unknown'", Integer.class));
    }

    @Test void olderVerifiableUpdateIsQuarantinedWithoutRevertingSnapshot() {
        String jid = "5511999999999@s.whatsapp.net";
        chats.apply(update(jid, true, "2026-10-26T10:00:00Z", "newer"));

        var result = chats.apply(update(jid, false, "2026-10-26T09:59:00Z", "older"));

        assertEquals(ChatStateService.ApplyResult.STALE, result);
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select archived from chat_snapshot where chat_jid=?", Boolean.class, jid));
        assertEquals(1, jdbc.queryForObject("select count(*) from quarantined_events where source_event_id='older'", Integer.class));
    }

    @Test void unarchiveClearsPendingNotificationsAtomically() {
        String jid = "5511999999999@s.whatsapp.net";
        chats.apply(update(jid, true, "2026-10-26T10:00:00Z", "archive"));
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at) values (?, '{}'::jsonb, now())", jid);

        chats.apply(update(jid, false, "2026-10-26T10:01:00Z", "unarchive"));

        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid));
    }

    @Test void readEventClearsPendingQueueByChat() {
        String jid = "5511999999999@s.whatsapp.net";
        chats.apply(update(jid, true, "2026-10-26T10:00:00Z", "archive"));
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at) values (?, '{}'::jsonb, now())", jid);

        assertEquals(1, chats.clearRead(jid, Optional.of(0), Optional.empty(), Optional.empty()));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid));

        jdbc.update("insert into pending_notification(chat_jid,payload,due_at) values (?, '{}'::jsonb, now())", jid);
        assertEquals(1, chats.clearRead(jid, Optional.empty(), Optional.of(false), Optional.of(4)));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid));
    }

    @Test void expiredSensitivePreviewsAndQuarantineRecordsArePurged() {
        Instant expired = Instant.parse("2026-10-01T00:00:00Z");
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at,expires_at) values (?, ?::jsonb, ?, ?)",
                "5511999999999@s.whatsapp.net", "{\"preview\":\"sensitive\"}", Timestamp.from(expired), Timestamp.from(expired));
        jdbc.update("insert into quarantined_events(event_kind,source_event_id,reason,payload,received_at,sensitive_expires_at) values ('MESSAGE','source-id','invalid','{}'::jsonb,?,?)",
                Timestamp.from(expired), Timestamp.from(expired));
        jdbc.update("insert into processed_message(message_id,processed_at) values ('hashed-dedup-key',?)",
                Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));

        int purged = new SensitiveDataRetentionService(jdbc).purge(Instant.parse("2026-10-02T00:00:00Z"));

        assertEquals(3, purged);
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from quarantined_events", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    private static ChatUpdate update(String jid, boolean archived, String timestamp, String id) {
        return new ChatUpdate(jid, Optional.of(archived), Instant.parse(timestamp), true, true,
                Optional.of(id), Optional.of("Contato"), false, Map.of("id", id));
    }
}
