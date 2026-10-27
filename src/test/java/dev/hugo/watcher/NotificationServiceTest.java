package dev.hugo.watcher;

import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class NotificationServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-26T11:00:00Z");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private WatcherProperties properties;
    private NotificationService notifications;
    private NotificationOutboxRepository outbox;
    private HttpServer server;
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<java.util.function.Consumer<String>> requestHook = new AtomicReference<>(ignored -> { });
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private MutableClock clock;

    @BeforeEach void setup() throws Exception {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.update("delete from outbox_actions");
        jdbc.update("delete from pending_notification");
        jdbc.update("delete from processed_message");
        jdbc.update("delete from quarantined_events");
        jdbc.update("delete from chat_snapshot");
        jdbc.update("delete from contact_identity");
        jdbc.update("delete from identity_aliases");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            requestCount.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedBody.set(body);
            requestHook.get().accept(body);
            int status = responseStatus.get();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();

        properties = new WatcherProperties();
        properties.setN8nUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
        properties.setN8nSecret("fixture-secret");
        clock = new MutableClock(NOW);
        manager = new DataSourceTransactionManager(dataSource);
        outbox = new NotificationOutboxRepository(jdbc, manager);
        var client = new N8nClient(properties, HttpClient.newHttpClient(), Duration.ofSeconds(2), List.of(Duration.ZERO, Duration.ZERO));
        notifications = new NotificationService(jdbc, manager, outbox, client, properties, clock);
    }

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void sendsTheLegacyPayloadShapeInTheConfiguredTimezoneAndAcknowledgesItsSnapshot() throws Exception {
        seed("5511999999999@s.whatsapp.net", "Ana", false, 2, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "Oi, tudo bem?", NOW);
        seed("120363000000000000@g.us", "Equipe", true, 3, "2026-10-26T09:05:00Z", "2026-10-26T10:30:00Z", "[Image]", NOW);

        notifications.flush(NOW);

        try (InputStream fixture = getClass().getResourceAsStream("/fixtures/notifications/archived_messages.json")) {
            assertNotNull(fixture, "legacy payload fixture must be available");
            assertEquals(JSON.readTree(fixture), JSON.readTree(receivedBody.get()));
        }
        assertFalse(jdbc.queryForObject("select payload::text from outbox_actions where action_type='archived_messages'", String.class).contains("fixture-secret"));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_actions where status='ACKNOWLEDGED'", Integer.class));
        assertEquals("{}", jdbc.queryForObject("select payload::text from outbox_actions where action_type='archived_messages'", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void defersWorkOnlyNotificationsOutsideTheLegacyWorkWindow() {
        seed("5511888888888@s.whatsapp.net", "Equipe JC", false, 1, "2026-10-26T14:00:00Z", "2026-10-26T15:00:00Z", "mensagem", NOW);

        clock.advance(Duration.ofHours(5));
        notifications.flush(clock.instant());

        assertNull(receivedBody.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_actions", Integer.class));
    }

    @Test void dropsNeverNotifyGroupsWithoutSendingTheirPendingContent() {
        seed("120363000000000001@g.us", "233/234", true, 1,
                "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "sensitive", NOW);

        notifications.flush(NOW);

        assertNull(receivedBody.get());
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_actions", Integer.class));
    }

    @Test void preservesANewerMessageThatArrivesWhileTheOutboxActionIsBeingAcknowledged() {
        String jid = "5511777777777@s.whatsapp.net";
        seed(jid, "Contato", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "primeira", NOW);
        requestHook.set(ignored -> jdbc.update("""
                update pending_notification set payload=jsonb_set(jsonb_set(payload, '{messageCount}', '2'::jsonb),
                    '{lastMessageAt}', to_jsonb('2026-10-26T10:01:00Z'::text)), updated_at=? where chat_jid=?
                """, java.sql.Timestamp.from(NOW.plusSeconds(1)), jid));

        notifications.flush(NOW);

        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid));
        assertEquals("2", jdbc.queryForObject("select payload->>'messageCount' from pending_notification where chat_jid=?", String.class, jid));
    }

    @Test void leavesTheOutboxAndPendingSnapshotForRetryWhenN8nReturnsFailure() {
        responseStatus.set(503);
        seed("5511666666666@s.whatsapp.net", "Contato", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "mensagem", NOW);

        notifications.flush(NOW);

        assertNotNull(receivedBody.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_actions where status='PENDING'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertFalse(jdbc.queryForObject("select payload::text from outbox_actions where status='PENDING'", String.class).contains("fixture-secret"));
    }

    @Test void schedulesRetryBackoffFromClockAfterHttpAttemptsFinish() {
        responseStatus.set(503);
        requestHook.set(ignored -> clock.advance(Duration.ofSeconds(15)));
        seed("5511111111111@s.whatsapp.net", "Contato", false, 1,
                "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "mensagem", NOW);

        notifications.flush(NOW);

        Instant availableAt = jdbc.queryForObject("select available_at from outbox_actions where status='PENDING'",
                java.sql.Timestamp.class).toInstant();
        assertEquals(NOW.plusSeconds(45), clock.instant(), "fake HTTP advances the injected clock for all attempts");
        assertEquals(clock.instant().plusSeconds(10), availableAt,
                "backoff must begin after the final failed HTTP attempt, not at flush start");
    }

    @Test void connectionAlertRetryStopsAndIsCancelledWhenReconnectOccursDuringHttpFailure() {
        var connections = new ConnectionService(jdbc, manager, outbox, properties, Duration.ofSeconds(60));
        connections.onState(new ConnectionEvent(ConnectionEvent.State.DISCONNECTED, NOW, "connection_lost", 408));
        outbox.enqueue("whatsapp_connection_lost:1", "whatsapp_connection_lost",
                "{\"eventType\":\"whatsapp_connection_lost\",\"manualPairingRequired\":false}",
                "[]", NOW, NOW.plusSeconds(7 * 86_400L));
        responseStatus.set(503);
        requestHook.set(ignored -> connections.onState(new ConnectionEvent(ConnectionEvent.State.CONNECTED, clock.instant(), "open", 200)));

        notifications.flush(NOW);

        assertEquals("CONNECTED", jdbc.queryForObject("select connection_state from watcher_connection_state where singleton_id=1", String.class));
        assertEquals(1, requestCount.get(), "the connection alert's internal HTTP retry must stop after reconnect");
        assertEquals("CANCELLED", jdbc.queryForObject("select status from outbox_actions where action_type='whatsapp_connection_lost'", String.class));
        assertEquals("{}", jdbc.queryForObject("select payload::text from outbox_actions where action_type='whatsapp_connection_lost'", String.class));

        responseStatus.set(200);
        requestHook.set(ignored -> { });
        clock.advance(Duration.ofSeconds(20));
        notifications.flush(clock.instant());

        assertEquals(1, requestCount.get(), "a cancelled alert must not be delivered by a later outbox retry");
    }

    @Test void revalidatesAQueuedGroupAgainstItsCurrentBlockedNameBeforeRetrying() {
        String jid = "5511555555555@s.whatsapp.net";
        responseStatus.set(503);
        seed(jid, "Contato", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "sensivel", NOW);
        notifications.flush(NOW);
        assertEquals(3, requestCount.get(), "first delivery exhausts the three configured HTTP attempts");

        jdbc.update("update chat_snapshot set display_name='233/234',is_group=true where chat_jid=?", jid);
        jdbc.update("update contact_identity set display_name='233/234' where canonical_jid=?", jid);
        responseStatus.set(200);

        clock.advance(Duration.ofSeconds(11));
        notifications.flush(clock.instant());

        assertEquals(3, requestCount.get(), "a now-blocked group must not reach the fake webhook");
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid),
                "suppression must not clear a pending snapshot that was not sent");
    }

    @Test void revalidatesWorkOnlyWindowAtRetryDeliveryTime() {
        responseStatus.set(503);
        seed("5511444444444@s.whatsapp.net", "Equipe JC", false, 1,
                "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "privado", NOW);
        notifications.flush(NOW);
        assertEquals(3, requestCount.get());
        responseStatus.set(200);

        clock.advance(Duration.ofHours(5));
        notifications.flush(clock.instant());

        assertEquals(3, requestCount.get(), "a WORK_ONLY retry outside the work window must not be sent");
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void filtersUnarchivedChatsAndKeepsConcurrentPendingSnapshotsOnRetry() throws Exception {
        String unarchived = "5511333333333@s.whatsapp.net";
        String concurrentlyUpdated = "5511222222222@s.whatsapp.net";
        responseStatus.set(503);
        seed(unarchived, "Contato antigo", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "não enviar", NOW);
        seed(concurrentlyUpdated, "Contato atual", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "mensagem antiga", NOW);
        notifications.flush(NOW);

        jdbc.update("update chat_snapshot set archived=false where chat_jid=?", unarchived);
        jdbc.update("update pending_notification set payload=jsonb_set(payload, '{messageCount}', '2'::jsonb), updated_at=? where chat_jid=?",
                java.sql.Timestamp.from(NOW.plusSeconds(1)), concurrentlyUpdated);
        responseStatus.set(200);
        clock.advance(Duration.ofSeconds(20));
        notifications.flush(clock.instant());

        var payload = JSON.readTree(receivedBody.get());
        assertEquals(1, payload.get("totalChats").intValue());
        assertEquals("5511222222222@s.whatsapp.net", payload.get("chats").get(0).get("jid").stringValue());
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, unarchived));
        assertEquals("2", jdbc.queryForObject("select payload->>'messageCount' from pending_notification where chat_jid=?", String.class, concurrentlyUpdated));
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));
    }

    @Test void purgesOutboxPayloadAtItsSensitiveRetentionDeadline() {
        outbox.enqueue("expired-preview", "archived_messages", "{\"preview\":\"synthetic\"}", "[]",
                NOW.minusSeconds(7 * 86_400L), NOW);

        new SensitiveDataRetentionService(jdbc).purge(NOW);

        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_actions where action_key='expired-preview'", Integer.class));
    }

    @Test void internalRetryFiltersUnarchivedChatAndAcknowledgesOnlyDeliveredSnapshot() throws Exception {
        String removed = "5511000000001@s.whatsapp.net";
        String eligible = "5511000000002@s.whatsapp.net";
        seed(removed, "Antigo", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "privado", NOW);
        seed(eligible, "Atual", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "ok", NOW);
        requestHook.set(ignored -> {
            if (requestCount.get() == 1) {
                jdbc.update("update chat_snapshot set archived=false where chat_jid=?", removed);
                responseStatus.set(503);
            } else responseStatus.set(200);
        });
        notifications.flush(NOW);
        assertEquals(2, requestCount.get());
        var payload = JSON.readTree(receivedBody.get());
        assertEquals(1, payload.get("totalChats").intValue());
        assertEquals(eligible, payload.get("chats").get(0).get("jid").stringValue());
        assertEquals(List.of(removed), jdbc.queryForList("select chat_jid from pending_notification", String.class));
    }

    @Test void internalRetryStopsWhenWorkWindowEndsAndPreservesDeferredPending() {
        seed("5511000000003@s.whatsapp.net", "Equipe JC", false, 1,
                "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "privado", NOW);
        responseStatus.set(503);
        requestHook.set(ignored -> clock.advance(Duration.ofHours(5)));
        notifications.flush(NOW);
        assertEquals(1, requestCount.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions", String.class));
    }

    @Test void transientConnectionStateReadFailureRetriesWithoutCancellingValidAlert() {
        assertConnectionReadFailureIsRetryable(false);
    }

    @Test void transientConnectionStateReadFailureAfter503StopsInternalPostsAndRetries() {
        assertConnectionReadFailureIsRetryable(true);
    }

    private void assertConnectionReadFailureIsRetryable(boolean afterFirstPost) {
        var connections = new ConnectionService(jdbc, manager, outbox, properties, Duration.ofSeconds(60));
        connections.onState(new ConnectionEvent(ConnectionEvent.State.DISCONNECTED, NOW, "connection_lost", 408));
        String key = jdbc.queryForObject("select 'whatsapp_connection_lost:' || outage_generation from watcher_connection_state where singleton_id=1", String.class);
        outbox.enqueue(key, "whatsapp_connection_lost", "{}", "[]", NOW, NOW.plusSeconds(86400));
        if (afterFirstPost) {
            responseStatus.set(503);
            requestHook.set(ignored -> jdbc.execute("alter table watcher_connection_state rename to unavailable_connection_state"));
        } else jdbc.execute("alter table watcher_connection_state rename to unavailable_connection_state");
        try {
            notifications.flush(NOW);
            assertEquals(afterFirstPost ? 1 : 0, requestCount.get());
            assertEquals("PENDING", jdbc.queryForObject("select status from outbox_actions where action_key=?", String.class, key));
        } finally {
            jdbc.execute("alter table unavailable_connection_state rename to watcher_connection_state");
        }
        responseStatus.set(200);
        requestHook.set(ignored -> { });
        clock.advance(Duration.ofSeconds(11));
        notifications.flush(clock.instant());
        assertEquals(afterFirstPost ? 2 : 1, requestCount.get());
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_key=?", String.class, key));
    }

    @Test void laterActionClaimReceivesFreshLeaseAfterLongEarlierDelivery() {
        outbox.enqueue("first", "other", "{}", "[]", NOW, NOW.plusSeconds(86400));
        outbox.enqueue("second", "other", "{}", "[]", NOW, NOW.plusSeconds(86400));
        AtomicReference<OutboxAction> stolen = new AtomicReference<>();
        requestHook.set(ignored -> {
            if (requestCount.get() == 1) clock.advance(Duration.ofSeconds(130));
            else stolen.set(outbox.claimNext(clock.instant()));
        });
        notifications.flush(NOW);
        assertNull(stolen.get(), "another worker must not steal the later action's fresh lease");
        assertEquals(2, requestCount.get());
        assertEquals(2, jdbc.queryForObject("select count(*) from outbox_actions where status='ACKNOWLEDGED'", Integer.class));
    }

    @Test void readThenRecreatedPendingDoesNotResendObsoletePreviewOnInternalRetry() {
        String jid = "5511000000091@s.whatsapp.net";
        seed(jid, "Contato", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "old sensitive preview", NOW);
        requestHook.set(ignored -> {
            if (requestCount.get() == 1) {
                new ChatStateService(jdbc, manager).clearRead(jid, NOW, java.util.Optional.of(0), java.util.Optional.empty(), java.util.Optional.empty());
                jdbc.update("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values (?,?::jsonb,?,?,?)",
                        jid, "{\"messageCount\":1,\"firstMessageAt\":\"2026-10-26T11:00:01Z\",\"lastMessageAt\":\"2026-10-26T11:00:01Z\",\"preview\":\"new preview\"}",
                        java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW.plusSeconds(86400)));
            }
            responseStatus.set(503);
        });
        notifications.flush(NOW);
        assertEquals(1, requestCount.get(), "READ terminates the old pending generation before retry");
        assertEquals("1", jdbc.queryForObject("select payload->>'messageCount' from pending_notification", String.class));
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions", String.class));
    }

    @Test void findsAnytimeAfterDeferredWorkOnlyRowsBeforeApplyingBatchLimit() throws Exception {
        properties.setMaxBatchSize(1);
        seed("5511000000092@s.whatsapp.net", "Equipe JC", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "work", NOW);
        seed("5511000000093@s.whatsapp.net", "Amigo", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:01:00Z", "anytime", NOW);
        clock.advance(Duration.ofHours(5));
        notifications.flush(clock.instant());
        assertNotNull(receivedBody.get(), "deferred first row must not starve eligible later row");
        assertEquals("5511000000093@s.whatsapp.net", JSON.readTree(receivedBody.get()).get("chats").get(0).get("jid").stringValue());
    }

    @Test void excludesExistingGroupPendingWhenGroupsDisabled() {
        properties.setIncludeGroups(false);
        seed("120363000000000091@g.us", "Grupo", true, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "group", NOW);
        notifications.flush(NOW);
        assertEquals(0, requestCount.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void disablingGroupsDuringDeliveryStopsInternalRetry() {
        seed("120363000000000092@g.us", "Grupo", true, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "group", NOW);
        requestHook.set(ignored -> { properties.setIncludeGroups(false); responseStatus.set(503); });
        notifications.flush(NOW);
        assertEquals(1, requestCount.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void acknowledgmentDoesNotDeleteRecreatedPendingWithIdenticalWatermarks() {
        String jid = "5511000000094@s.whatsapp.net";
        seed(jid, "Contato", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "preview", NOW);
        requestHook.set(ignored -> recreatePendingIdentically(jid));
        notifications.flush(NOW);
        assertEquals(1, requestCount.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class),
                "ACK must compare generation even when every payload watermark is identical");
    }

    @Test void durableRetryDoesNotResendPreviousArchiveGeneration() {
        String jid = "5511000000095@s.whatsapp.net";
        seed(jid, "Contato", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "obsolete preview", NOW);
        responseStatus.set(503);
        notifications.flush(NOW);
        assertEquals(3, requestCount.get());
        jdbc.update("update chat_snapshot set archived=false where chat_jid=?", jid);
        recreatePendingIdentically(jid);
        jdbc.update("update chat_snapshot set archived=true where chat_jid=?", jid);
        responseStatus.set(200);
        clock.advance(Duration.ofSeconds(11));
        notifications.flush(clock.instant());
        assertEquals(3, requestCount.get(), "rearchive must not authorize the previous incarnation");
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        notifications.flush(clock.instant());
        assertEquals(4, requestCount.get(), "new generation remains deliverable on the next batch");
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    private void recreatePendingIdentically(String jid) {
        var row = jdbc.queryForMap("select payload::text,due_at,updated_at,expires_at from pending_notification where chat_jid=?", jid);
        jdbc.update("delete from pending_notification where chat_jid=?", jid);
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values (?,?::jsonb,?,?,?)",
                jid, row.get("payload"), row.get("due_at"), row.get("updated_at"), row.get("expires_at"));
    }

    private void seed(String jid, String name, boolean group, int count, String first, String last, String preview, Instant now) {
        Instant updated = now.minusSeconds(3600);
        Instant expires = updated.plusSeconds(7 * 86400L);
        Instant previewExpires = expires;
        jdbc.update("""
                insert into chat_snapshot(chat_jid, archived, snapshot_at, display_name, is_group, synchronized_snapshot)
                values (?, true, ?, ?, ?, true)
                """, jid, java.sql.Timestamp.from(updated), name, group);
        if (!group) {
            String phoneJid = jid.endsWith("@s.whatsapp.net") ? jid : null;
            String lidJid = "Ana".equals(name) ? "1001@lid" : null;
            String username = "Ana".equals(name) ? "ana" : null;
            jdbc.update("""
                    insert into contact_identity(canonical_jid, phone_jid, lid_jid, username, display_name, display_name_source, updated_at)
                    values (?, ?, ?, ?, ?, 'CONTACT_NAME', ?)
                    """, jid, phoneJid, lidJid, username, name, java.sql.Timestamp.from(updated));
            jdbc.update("insert into identity_aliases(alias, canonical_identity) values (?, ?)", jid, jid);
            if (lidJid != null) jdbc.update("insert into identity_aliases(alias, canonical_identity) values (?, ?)", lidJid, jid);
        }
        String payload = JSON.writeValueAsString(Map.of(
                "messageCount", count,
                "firstMessageAt", first,
                "lastMessageAt", last,
                "preview", preview,
                "previewExpiresAt", previewExpires.toString(),
                "isGroup", group,
                "notificationPolicy", name.contains("JC") ? "WORK_ONLY" : "ANYTIME"));
        jdbc.update("""
                insert into pending_notification(chat_jid, payload, due_at, updated_at, expires_at)
                values (?, ?::jsonb, ?, ?, ?)
                """, jid, payload, java.sql.Timestamp.from(Instant.parse(last)), java.sql.Timestamp.from(updated), java.sql.Timestamp.from(expires));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) { now = new AtomicReference<>(initial); }
        private void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
