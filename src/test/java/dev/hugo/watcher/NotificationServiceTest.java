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
import tools.jackson.databind.json.JsonMapper;
import dev.hugo.watcher.notification.IntelligentAlertService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.sql.Timestamp;
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
    private ArchiveModeService archiveModes;
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final AtomicReference<Duration> retryAfter = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<java.util.function.Consumer<String>> requestHook = new AtomicReference<>(ignored -> { });
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private MutableClock clock;

    @BeforeEach void setup() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.update("delete from decision_delivery_attempts");
        jdbc.update("delete from decision_delivery_claims");
        jdbc.update("delete from outbox_actions");
        jdbc.update("delete from pending_notification");
        jdbc.update("delete from processed_message");
        jdbc.update("delete from quarantined_events");
        jdbc.update("delete from chat_snapshot");
        jdbc.update("delete from contact_identity");
        jdbc.update("delete from identity_aliases");
        jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=null where singleton_id=1");

        properties = new WatcherProperties();
        properties.setTelegramBotToken("synthetic-test-token");
        properties.setTelegramChatId(123456789L);
        clock = new MutableClock(NOW);
        manager = new DataSourceTransactionManager(dataSource);
        archiveModes = new ArchiveModeService(jdbc, manager);
        archiveModes.setMode(ArchiveMode.ALL);
        outbox = new NotificationOutboxRepository(jdbc, manager, Duration.ZERO);
        TelegramTransport fakeTelegram = text -> {
            requestCount.incrementAndGet();
            receivedBody.set(text);
            requestHook.get().accept(text);
            return responseStatus.get() == 200
                    ? TelegramDeliveryResult.success()
                    : TelegramDeliveryResult.failure(retryAfter.get());
        };
        notifications = new NotificationService(jdbc, manager, outbox, fakeTelegram,
                new TelegramMessageFormatter(), properties, clock, archiveModes,
                new IntelligentAlertService(jdbc, manager, outbox, clock, true, "15550000000@s.whatsapp.net"));
    }

    @Test void sendsAReadableTelegramSummaryAndAcknowledgesItsSnapshot() {
        seed("5511999999999@s.whatsapp.net", "Ana", false, 2, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "Oi, tudo bem?", NOW);
        seed("120363000000000000@g.us", "Equipe", true, 3, "2026-10-26T09:05:00Z", "2026-10-26T10:30:00Z", "[Image]", NOW);

        notifications.flush(NOW);

        assertTrue(receivedBody.get().contains("Mensagens arquivadas"));
        assertTrue(receivedBody.get().contains("2 conversas · 5 mensagens"));
        assertTrue(receivedBody.get().contains("Ana"));
        assertTrue(receivedBody.get().contains("Oi, tudo bem?"));
        assertFalse(jdbc.queryForObject("select payload::text from outbox_actions where action_type='archived_messages'", String.class).contains("synthetic-test-token"));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_actions where status='ACKNOWLEDGED'", Integer.class));
        assertEquals("{}", jdbc.queryForObject("select payload::text from outbox_actions where action_type='archived_messages'", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void emergencyKillSwitchDoesNotBlockQueuedTelegramAdminReply() {
        Instant now = Instant.now();
        jdbc.update("update automation_settings set kill_switch_enabled=true,intelligent_alerts_enabled=false,auto_reply_enabled=false where singleton_id=1");
        outbox.enqueue("telegram_admin_reply:kill-switch", "telegram_admin_reply",
                "{\"eventType\":\"telegram_admin_reply\",\"text\":\"Admin response\"}", "[]", now, now.plus(Duration.ofMinutes(5)));

        notifications.flush(now);

        assertEquals(1, requestCount.get());
        assertEquals("Admin response", receivedBody.get());
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_type='telegram_admin_reply'", String.class));
    }

    @Test void revalidatesIntelligentAlertAfterEnqueueAndSuppressesRevokedAuthorization() {
        clock.set(Instant.now());
        Instant now = clock.instant();
        UUID decisionId = UUID.randomUUID();
        Instant expires = now.plus(Duration.ofDays(1));
        jdbc.update("update automation_settings set ai_classification_enabled=true,intelligent_alerts_enabled=true,kill_switch_enabled=false where singleton_id=1");
        jdbc.update("insert into response_templates(template_id,version,situation,body,identifies_automation,enabled) values ('notify_alert_test',1,'CRITICAL_URGENCY','Resposta automática: alerta crítico.',true,true)");
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,?,'COMPLETED',?,?,?,?,?,?)",
                decisionId, "15551234567@s.whatsapp.net", "", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expires), Timestamp.from(expires));
        jdbc.update("insert into simulation_batch_messages(batch_id,chat_jid,participant_jid,external_id,received_at,payload) values (?,?,?,?,?,?::jsonb)",
                decisionId, "15551234567@s.whatsapp.net", "", "notify-source", Timestamp.from(now),
                "{\"fromMe\":false,\"content\":{\"extendedTextMessage\":{\"text\":\"RAW_TEST_SECRET\",\"contextInfo\":{\"mentionedJid\":[\"15550000000@s.whatsapp.net\"]}}}}");
        jdbc.update("""
                insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at,
                    real_action_allowed,decision_state,template_id,classification_status,classification_urgency,
                    classification_confidence,intelligent_alert_reference_hash)
                values (?,true,false,'synthetic_audit',?,?,true,'WOULD_ACT','notify_alert_test','CLASSIFIED','HIGH',0.9,?)
                """, decisionId, Timestamp.from(now), Timestamp.from(expires), hash("notify-source"));
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CRITICAL_URGENCY','notify_alert_test',1)", "15551234567@s.whatsapp.net");
        jdbc.update("update simulation_decisions set production_mode=true,alert_template_reference='notify_alert_test@v1' where batch_id=?",decisionId);
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values (?,'CONTACT_REQUEST','gemini',true,?)", "15551234567@s.whatsapp.net",Timestamp.from(expires));
        jdbc.update("insert into simulation_ai_evaluations(batch_id,lease_token,expires_at,result) values (?,?,?,?::jsonb)",decisionId,UUID.randomUUID(),Timestamp.from(expires),
                "{\"provider\":\"gemini\",\"purposes\":[\"CONTACT_REQUEST\"],\"available\":true,\"requestDetected\":true,\"status\":\"CLASSIFIED\",\"urgency\":\"HIGH\",\"confidence\":0.9}");
        var alerts = new IntelligentAlertService(jdbc, manager, outbox, clock, true, "15550000000@s.whatsapp.net");
        var queued = alerts.enqueue(decisionId);
        assertTrue(queued.accepted());
        assertFalse(queued.action().payload().contains("RAW_TEST_SECRET"));
        jdbc.update("update outbox_actions set available_at=? where action_key=?",
                Timestamp.from(now.minusSeconds(1)), "intelligent_alert:" + decisionId);

        jdbc.update("update automation_settings set kill_switch_enabled=true where singleton_id=1");
        notifications.flush(now);

        assertEquals(0, requestCount.get());
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_key=?", String.class,
                "intelligent_alert:" + decisionId));
        assertEquals("{}", jdbc.queryForObject("select payload::text from outbox_actions where action_key=?", String.class,
                "intelligent_alert:" + decisionId));
    }

    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
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

    @Test void leavesTheOutboxAndPendingSnapshotForDurableRetryWhenTelegramRejectsDelivery() {
        responseStatus.set(503);
        seed("5511666666666@s.whatsapp.net", "Contato", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "mensagem", NOW);

        notifications.flush(NOW);

        assertNotNull(receivedBody.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_actions where status='PENDING'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertFalse(jdbc.queryForObject("select payload::text from outbox_actions where status='PENDING'", String.class).contains("synthetic-test-token"));
    }

    @Test void schedulesDurableRetryBackoffAfterTelegramRejectsDelivery() {
        responseStatus.set(503);
        requestHook.set(ignored -> clock.advance(Duration.ofSeconds(15)));
        seed("5511111111111@s.whatsapp.net", "Contato", false, 1,
                "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "mensagem", NOW);

        Instant databaseBefore = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        notifications.flush(NOW);

        Instant availableAt = jdbc.queryForObject("select available_at from outbox_actions where status='PENDING'",
                java.sql.Timestamp.class).toInstant();
        Instant databaseAfter = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        assertEquals(NOW.plusSeconds(15), clock.instant(), "fake transport advances the injected clock once");
        assertTrue(!availableAt.isBefore(databaseBefore.plusSeconds(10))
                        && !availableAt.isAfter(databaseAfter.plusSeconds(11)),
                "durable retry backoff must start from PostgreSQL time");
    }

    @Test void TelegramRetryAfterBlocksEveryOutboxMessageUntilServerDelayExpires() {
        outbox = new NotificationOutboxRepository(jdbc, manager, Duration.ofSeconds(1));
        notifications = new NotificationService(jdbc, manager, outbox, fakeTransport(),
                new TelegramMessageFormatter(), properties, clock, archiveModes);
        outbox.enqueue("first", "other", "{}", "[]", NOW, NOW.plusSeconds(5 * 86400L));
        outbox.enqueue("second", "other", "{}", "[]", NOW, NOW.plusSeconds(5 * 86400L));
        responseStatus.set(429);
        retryAfter.set(Duration.ofSeconds(30));

        Instant databaseBefore = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        notifications.flush(NOW);

        assertEquals(1, requestCount.get(), "a 429 must stop this flush from sending the next queued message");
        Instant availableAt = jdbc.queryForObject("select available_at from outbox_actions where action_key='first'",
                java.sql.Timestamp.class).toInstant();
        Instant nextAllowedAt = jdbc.queryForObject("select next_allowed_at from telegram_delivery_rate_limit where singleton_id=1",
                java.sql.Timestamp.class).toInstant();
        Instant databaseAfter = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        assertTrue(!availableAt.isBefore(databaseBefore.plusSeconds(30).plusMillis(250))
                        && !availableAt.isAfter(databaseAfter.plusSeconds(31)),
                "retry_after deadline must use PostgreSQL time");
        assertEquals(availableAt, nextAllowedAt);
        clock.advance(Duration.ofDays(2));
        notifications.flush(clock.instant());
        assertEquals(1, requestCount.get(), "an ahead client clock must not bypass retry_after cooldown");
        jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=clock_timestamp()-interval '1 second' where singleton_id=1");
        jdbc.update("update outbox_actions set available_at=clock_timestamp()-interval '1 second' where status='PENDING'");
        responseStatus.set(200);
        notifications.flush(clock.instant());
        jdbc.update("update telegram_delivery_rate_limit set next_allowed_at=clock_timestamp()-interval '1 second' where singleton_id=1");
        notifications.flush(clock.instant());
        assertEquals(3, requestCount.get());
    }

    @Test void Telegram429WithoutRetryAfterUsesSharedConservativeCooldown() {
        outbox = new NotificationOutboxRepository(jdbc, manager, Duration.ofSeconds(1));
        notifications = new NotificationService(jdbc, manager, outbox, fakeTransport(),
                new TelegramMessageFormatter(), properties, clock, archiveModes);
        outbox.enqueue("limited", "other", "{}", "[]", NOW, NOW.plusSeconds(5 * 86400L));
        outbox.enqueue("queued", "other", "{}", "[]", NOW, NOW.plusSeconds(5 * 86400L));
        responseStatus.set(429);
        retryAfter.set(null);

        Instant databaseBefore = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        notifications.flush(NOW);

        assertEquals(1, requestCount.get());
        Instant availableAt = jdbc.queryForObject("select available_at from outbox_actions where action_key='limited'",
                java.sql.Timestamp.class).toInstant();
        Instant nextAllowedAt = jdbc.queryForObject("select next_allowed_at from telegram_delivery_rate_limit where singleton_id=1",
                java.sql.Timestamp.class).toInstant();
        Instant databaseAfter = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        assertTrue(!availableAt.isBefore(databaseBefore.plusSeconds(60))
                        && !availableAt.isAfter(databaseAfter.plusSeconds(61)),
                "missing retry_after must schedule a conservative durable action delay");
        assertEquals(availableAt, nextAllowedAt, "global cooldown must match retry deadline");

        clock.advance(Duration.ofDays(2));
        notifications.flush(clock.instant());
        assertEquals(1, requestCount.get(), "other queued messages must remain blocked during the global cooldown");
    }

    @Test void PostgreSqlRateLimitCoordinatesIndependentOutboxRepositoryInstances() {
        var firstWorker = new NotificationOutboxRepository(jdbc, manager, Duration.ofSeconds(1));
        var secondWorker = new NotificationOutboxRepository(jdbc, manager, Duration.ofSeconds(1));
        Instant createdAt = Instant.now().minusSeconds(1);
        Instant expiresAt = createdAt.plus(Duration.ofDays(6));
        firstWorker.enqueue("worker-a", "other", "{}", "[]", createdAt, expiresAt);
        firstWorker.enqueue("worker-b", "other", "{}", "[]", createdAt, expiresAt);

        Instant databaseBefore = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        Instant clientNow = Instant.now();
        assertEquals("worker-a", firstWorker.claimNext(clientNow).actionKey());
        Instant nextAllowedAt = jdbc.queryForObject("select next_allowed_at from telegram_delivery_rate_limit where singleton_id=1",
                java.sql.Timestamp.class).toInstant();
        Instant databaseAfter = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        assertTrue(!nextAllowedAt.isBefore(databaseBefore.plusSeconds(1))
                        && !nextAllowedAt.isAfter(databaseAfter.plusSeconds(1)),
                "rate reservation must use PostgreSQL time, not the worker clock");
        assertNull(secondWorker.claimNext(clientNow.plus(Duration.ofDays(1))),
                "a worker clock ahead of PostgreSQL must not bypass the shared cooldown");
    }

    @Test void genericRetryDeadlineUsesDatabaseClockAndCannotBeClaimedByAnAheadWorker() {
        var worker = new NotificationOutboxRepository(jdbc, manager, Duration.ZERO);
        Instant databaseNow = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        Instant createdAt = databaseNow.minusSeconds(5);
        worker.enqueue("generic-retry", "other", "{}", "[]", createdAt, databaseNow.plus(Duration.ofDays(5)));
        OutboxAction action = worker.claimNext(databaseNow);

        worker.retry(action, databaseNow.plus(Duration.ofDays(1)), "TemporaryFailure");

        Instant availableAt = jdbc.queryForObject("select available_at from outbox_actions where action_key='generic-retry'",
                java.sql.Timestamp.class).toInstant();
        Instant databaseAfter = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        assertTrue(!availableAt.isBefore(databaseNow.plusSeconds(10))
                        && !availableAt.isAfter(databaseAfter.plusSeconds(11)),
                "generic retry backoff must start from PostgreSQL time");
        assertNull(worker.claimNext(databaseNow.plus(Duration.ofDays(1))),
                "a worker clock ahead of PostgreSQL must not make a durable retry eligible early");
    }

    @Test void activeLeaseCannotBeReclaimedByAnAheadWorkerButExpiredLeaseCan() {
        var firstWorker = new NotificationOutboxRepository(jdbc, manager, Duration.ZERO);
        var secondWorker = new NotificationOutboxRepository(jdbc, manager, Duration.ZERO);
        Instant databaseNow = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        firstWorker.enqueue("leased", "other", "{}", "[]", databaseNow.minusSeconds(1),
                databaseNow.plus(Duration.ofDays(5)));

        assertEquals("leased", firstWorker.claimNext(databaseNow).actionKey());
        assertNull(secondWorker.claimNext(databaseNow.plus(Duration.ofMinutes(3))),
                "a worker clock ahead must not reclaim an unexpired lease");

        jdbc.update("update outbox_actions set lease_expires_at=clock_timestamp()-interval '1 second' where action_key='leased'");
        assertEquals("leased", secondWorker.claimNext(databaseNow).actionKey(),
                "a lease that expired by PostgreSQL time must remain reclaimable");
    }

    @Test void databaseExpiredSensitivePayloadIsNotClaimedByABehindWorkerClock() {
        var worker = new NotificationOutboxRepository(jdbc, manager, Duration.ZERO);
        Instant databaseNow = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        worker.enqueue("expired", "other", "{}", "[]", databaseNow.minusSeconds(10), databaseNow.minusSeconds(1));

        assertNull(worker.claimNext(databaseNow.minusSeconds(2)),
                "database expiry must not be extended by a worker clock that is behind");
        assertEquals("PENDING", jdbc.queryForObject("select status from outbox_actions where action_key='expired'", String.class));
    }

    @Test void enqueueCapsSensitiveTtlAtSevenDaysFromDatabaseTimeButKeepsEarlierContentDeadline() {
        Instant databaseBefore = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        Instant workerCreatedAt = databaseBefore.plus(Duration.ofDays(2));
        Instant workerMaximumExpiry = workerCreatedAt.plus(Duration.ofDays(7));
        Instant shortContentExpiry = databaseBefore.plus(Duration.ofDays(2));

        outbox.enqueue("worker-clock-ahead", "other", "{}", "[]", workerCreatedAt, workerMaximumExpiry);
        outbox.enqueue("short-content-deadline", "other", "{}", "[]", workerCreatedAt, shortContentExpiry);

        Instant databaseAfter = jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
        Instant maxExpiry = jdbc.queryForObject("select sensitive_expires_at from outbox_actions where action_key='worker-clock-ahead'",
                java.sql.Timestamp.class).toInstant();
        Instant retainedShortExpiry = jdbc.queryForObject("select sensitive_expires_at from outbox_actions where action_key='short-content-deadline'",
                java.sql.Timestamp.class).toInstant();

        assertTrue(!maxExpiry.isAfter(databaseAfter.plus(Duration.ofDays(7))),
                "a worker clock ahead must not extend sensitive retention beyond seven days from database insert time");
        assertEquals(shortContentExpiry, retainedShortExpiry,
                "database cap must preserve a shorter content-specific deadline");
    }

    private TelegramTransport fakeTransport() {
        return text -> {
            requestCount.incrementAndGet();
            receivedBody.set(text);
            requestHook.get().accept(text);
            if (responseStatus.get() == 200) return TelegramDeliveryResult.success();
            if (responseStatus.get() == 429) return TelegramDeliveryResult.rateLimited(retryAfter.get());
            return TelegramDeliveryResult.failure(retryAfter.get());
        };
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
        assertEquals(1, requestCount.get(), "one direct Telegram attempt is made");
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
        assertEquals(1, requestCount.get(), "a rejected Telegram send is retried through the durable outbox");

        jdbc.update("update chat_snapshot set display_name='233/234',is_group=true where chat_jid=?", jid);
        jdbc.update("update contact_identity set display_name='233/234' where canonical_jid=?", jid);
        responseStatus.set(200);

        clock.advance(Duration.ofSeconds(11));
        makePendingOutboxDue();
        notifications.flush(clock.instant());

        assertEquals(1, requestCount.get(), "a now-blocked group must not reach Telegram");
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid),
                "suppression must not clear a pending snapshot that was not sent");
    }

    @Test void revalidatesWorkOnlyWindowAtRetryDeliveryTime() {
        responseStatus.set(503);
        seed("5511444444444@s.whatsapp.net", "Equipe JC", false, 1,
                "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "privado", NOW);
        notifications.flush(NOW);
        assertEquals(1, requestCount.get());
        responseStatus.set(200);

        clock.advance(Duration.ofHours(5));
        makePendingOutboxDue();
        notifications.flush(clock.instant());

        assertEquals(1, requestCount.get(), "a WORK_ONLY retry outside the work window must not be sent");
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void filtersUnarchivedChatsAndKeepsConcurrentPendingSnapshotsOnRetry() throws Exception {
        archiveModes.setMode(ArchiveMode.ARCHIVED_ONLY);
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
        makePendingOutboxDue();
        notifications.flush(clock.instant());

        assertTrue(receivedBody.get().contains("Contato atual"));
        assertFalse(receivedBody.get().contains("Contato antigo"));
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

    @Test void durableRetryFiltersUnarchivedChatAndAcknowledgesOnlyDeliveredSnapshot() {
        archiveModes.setMode(ArchiveMode.ARCHIVED_ONLY);
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
        clock.advance(Duration.ofSeconds(11));
        makePendingOutboxDue();
        notifications.flush(clock.instant());
        assertEquals(2, requestCount.get());
        assertTrue(receivedBody.get().contains("Atual"));
        assertFalse(receivedBody.get().contains("Antigo"));
        assertEquals(List.of(removed), jdbc.queryForList("select chat_jid from pending_notification", String.class));
    }

    @Test void unarchivedOnlyModeDeliversOnlySynchronizedUnarchivedChats() throws Exception {
        String jid = "5511444444444@s.whatsapp.net";
        seed(jid, "Contato", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "mensagem", NOW);
        jdbc.update("update chat_snapshot set archived=false where chat_jid=?", jid);
        archiveModes.setMode(ArchiveMode.UNARCHIVED_ONLY);

        notifications.flush(NOW);

        assertNotNull(receivedBody.get());
        assertTrue(receivedBody.get().contains("Contato"));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void modeChangeSuppressesRetryAndAllModeCanReleasePendingForUnknownArchiveState() throws Exception {
        String jid = "5511555555555@s.whatsapp.net";
        archiveModes.setMode(ArchiveMode.ARCHIVED_ONLY);
        seed(jid, "Contato", true, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "mensagem", NOW);
        responseStatus.set(503);
        notifications.flush(NOW);
        assertEquals("PENDING", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));

        jdbc.update("update chat_snapshot set archived=null,synchronized_snapshot=false where chat_jid=?", jid);
        archiveModes.setMode(ArchiveMode.ARCHIVED_ONLY);
        responseStatus.set(200);
        clock.advance(Duration.ofSeconds(20));
        jdbc.update("update outbox_actions set available_at=?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
        notifications.flush(clock.instant());

        assertEquals(1, requestCount.get(), "filtered retry must not issue another Telegram message");
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid));
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));

        archiveModes.setMode(ArchiveMode.ALL);
        notifications.flush(clock.instant());
        assertEquals(2, requestCount.get());
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid));
    }

    @Test void modeChangeBetweenFailureAndRetrySuppressesPersistedAction() {
        String jid = "5511555555556@s.whatsapp.net";
        archiveModes.setMode(ArchiveMode.ARCHIVED_ONLY);
        seed(jid, "Contato", true, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "mensagem", NOW);
        responseStatus.set(503);
        notifications.flush(NOW);
        assertEquals("PENDING", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));
        var retryAt = jdbc.queryForObject("select available_at from outbox_actions where action_type='archived_messages'", java.sql.Timestamp.class);
        notifications.flush(clock.instant());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_actions where action_type='archived_messages'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select attempts from outbox_actions where action_type='archived_messages'", Integer.class));
        assertEquals(retryAt, jdbc.queryForObject("select available_at from outbox_actions where action_type='archived_messages'", java.sql.Timestamp.class));

        archiveModes.setMode(ArchiveMode.UNARCHIVED_ONLY);
        responseStatus.set(200);
        clock.advance(Duration.ofSeconds(20));
        jdbc.update("update outbox_actions set available_at=?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
        notifications.flush(clock.instant());

        assertEquals(1, requestCount.get(), "retry must revalidate the persisted action against the current mode");
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid));
    }

    @Test void repeatedFlushReusesSuppressedGenerationWithoutCreatingDuplicateOutboxRows() {
        String jid = "5511555555557@s.whatsapp.net";
        archiveModes.setMode(ArchiveMode.ARCHIVED_ONLY);
        seed(jid, "Contato", true, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "mensagem", NOW);
        String generation = jdbc.queryForObject("select generation::text from pending_notification where chat_jid=?", String.class, jid);
        responseStatus.set(503);
        notifications.flush(NOW);

        jdbc.update("update chat_snapshot set archived=false where chat_jid=?", jid);
        clock.advance(Duration.ofSeconds(20));
        jdbc.update("update outbox_actions set available_at=?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
        notifications.flush(clock.instant());
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_type='archived_messages'", String.class));
        assertEquals(generation, jdbc.queryForObject("select generation::text from pending_notification where chat_jid=?", String.class, jid));

        jdbc.update("update chat_snapshot set archived=true where chat_jid=?", jid);
        responseStatus.set(200);
        clock.advance(Duration.ofSeconds(1));
        notifications.flush(clock.instant());

        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_actions where action_type='archived_messages'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, jid));
    }

    @Test void failedTelegramSendKeepsOutboxForRetryWhenWorkWindowEnds() {
        seed("5511000000003@s.whatsapp.net", "Equipe JC", false, 1,
                "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "privado", NOW);
        responseStatus.set(503);
        requestHook.set(ignored -> clock.advance(Duration.ofHours(5)));
        notifications.flush(NOW);
        assertEquals(1, requestCount.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals("PENDING", jdbc.queryForObject("select status from outbox_actions", String.class));
    }

    @Test void transientConnectionStateReadFailureRetriesWithoutCancellingValidAlert() {
        assertConnectionReadFailureIsRetryable(false);
    }

    @Test void transientConnectionStateReadFailureAfterTelegramAttemptRetries() {
        assertConnectionReadFailureIsRetryable(true);
    }

    private void assertConnectionReadFailureIsRetryable(boolean afterTelegramSend) {
        var connections = new ConnectionService(jdbc, manager, outbox, properties, Duration.ofSeconds(60));
        connections.onState(new ConnectionEvent(ConnectionEvent.State.DISCONNECTED, NOW, "connection_lost", 408));
        String key = jdbc.queryForObject("select 'whatsapp_connection_lost:' || outage_generation from watcher_connection_state where singleton_id=1", String.class);
        outbox.enqueue(key, "whatsapp_connection_lost", "{}", "[]", NOW, NOW.plusSeconds(5 * 86400L));
        if (afterTelegramSend) {
            responseStatus.set(503);
            requestHook.set(ignored -> jdbc.execute("alter table watcher_connection_state rename to unavailable_connection_state"));
        } else jdbc.execute("alter table watcher_connection_state rename to unavailable_connection_state");
        try {
            notifications.flush(NOW);
            assertEquals(afterTelegramSend ? 1 : 0, requestCount.get());
            assertEquals("PENDING", jdbc.queryForObject("select status from outbox_actions where action_key=?", String.class, key));
        } finally {
            jdbc.execute("alter table unavailable_connection_state rename to watcher_connection_state");
        }
        responseStatus.set(200);
        requestHook.set(ignored -> { });
        clock.advance(Duration.ofSeconds(11));
        makePendingOutboxDue();
        notifications.flush(clock.instant());
        assertEquals(afterTelegramSend ? 2 : 1, requestCount.get());
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select status from outbox_actions where action_key=?", String.class, key));
    }

    @Test void laterActionClaimReceivesFreshLeaseAfterLongEarlierDelivery() {
        outbox.enqueue("first", "other", "{}", "[]", NOW, NOW.plusSeconds(5 * 86400L));
        outbox.enqueue("second", "other", "{}", "[]", NOW, NOW.plusSeconds(5 * 86400L));
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

    @Test void readThenRecreatedPendingDoesNotResendObsoletePreviewOnDurableRetry() {
        String jid = "5511000000091@s.whatsapp.net";
        seed(jid, "Contato", false, 1, "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", "old sensitive preview", NOW);
        requestHook.set(ignored -> {
            if (requestCount.get() == 1) {
                new ChatStateService(jdbc, manager, new ArchiveModeService(jdbc, manager)).clearRead(jid, NOW, java.util.Optional.of(0), java.util.Optional.empty(), java.util.Optional.empty());
                jdbc.update("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values (?,?::jsonb,?,?,?)",
                        jid, "{\"messageCount\":1,\"firstMessageAt\":\"2026-10-26T11:00:01Z\",\"lastMessageAt\":\"2026-10-26T11:00:01Z\",\"preview\":\"new preview\"}",
                        java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW.plusSeconds(86400)));
            }
            responseStatus.set(503);
        });
        notifications.flush(NOW);
        clock.advance(Duration.ofSeconds(11));
        makePendingOutboxDue();
        jdbc.update("update outbox_actions set available_at=?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
        notifications.flush(clock.instant());
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
        assertTrue(receivedBody.get().contains("Amigo"));
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
        assertEquals(1, requestCount.get());
        jdbc.update("update chat_snapshot set archived=false where chat_jid=?", jid);
        recreatePendingIdentically(jid);
        jdbc.update("update chat_snapshot set archived=true where chat_jid=?", jid);
        responseStatus.set(200);
        clock.advance(Duration.ofSeconds(11));
        makePendingOutboxDue();
        notifications.flush(clock.instant());
        assertEquals(1, requestCount.get(), "rearchive must not authorize the previous incarnation");
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        notifications.flush(clock.instant());
        assertEquals(2, requestCount.get(), "new generation remains deliverable on the next batch");
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    private void recreatePendingIdentically(String jid) {
        var row = jdbc.queryForMap("select payload::text,due_at,updated_at,expires_at from pending_notification where chat_jid=?", jid);
        jdbc.update("delete from pending_notification where chat_jid=?", jid);
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values (?,?::jsonb,?,?,?)",
                jid, row.get("payload"), row.get("due_at"), row.get("updated_at"), row.get("expires_at"));
    }

    private void makePendingOutboxDue() {
        jdbc.update("update outbox_actions set available_at=clock_timestamp()-interval '1 second' where status='PENDING'");
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
        private void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
