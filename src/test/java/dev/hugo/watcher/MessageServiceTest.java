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
import dev.hugo.watcher.simulation.DebounceService;
import dev.hugo.watcher.simulation.WatcherSimulationProperties;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MessageServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private ChatStateService chats;
    private MessageService messages;
    private IdentityService identities;
    private ArchiveModeService archiveModes;
    private WatcherProperties properties;

    @BeforeEach void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batch_messages");
        jdbc.update("delete from simulation_batches");
        jdbc.update("delete from pending_notification");
        jdbc.update("delete from processed_message");
        jdbc.update("delete from quarantined_events");
        jdbc.update("delete from chat_snapshot");
        jdbc.update("delete from contact_identity");
        jdbc.update("delete from identity_aliases");
        var manager = new DataSourceTransactionManager(dataSource);
        archiveModes = new ArchiveModeService(jdbc, manager);
        archiveModes.setMode(ArchiveMode.ALL);
        chats = new ChatStateService(jdbc, manager, archiveModes);
        properties = new WatcherProperties();
        identities = new IdentityService(jdbc, manager);
        messages = new MessageService(jdbc, manager, identities, properties, archiveModes);
    }

    @Test void allModeAcceptsUnknownArchiveStateAndDeduplicatesMessage() {
        var result = messages.accept(message("m-unknown", "5511999999999@s.whatsapp.net", Map.of("conversation", "Oi")));

        assertEquals(MessageService.Acceptance.ACCEPTED, result);
        assertEquals(MessageService.Acceptance.DUPLICATE, messages.accept(message("m-unknown", "5511999999999@s.whatsapp.net", Map.of("conversation", "Oi"))));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    @Test void acceptedEvolutionMessageEntersSimulationQueueOnce() {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var integrated = new MessageService(jdbc, manager, identities, properties, archiveModes,
                new DebounceService(jdbc, new WatcherSimulationProperties(), manager));
        var message = message("shadow-queued", "5511999999999@s.whatsapp.net", Map.of("conversation", "Oi"));

        assertEquals(MessageService.Acceptance.ACCEPTED, integrated.accept(message));
        assertEquals(MessageService.Acceptance.DUPLICATE, integrated.accept(message));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message where shadow_payload is not null", Integer.class));
        new DebounceService(jdbc, new WatcherSimulationProperties(), manager).claimReady(Instant.now());
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_batch_messages", Integer.class));
    }

    @Test void disabledSimulationDoesNotQueueAcceptedMessages() {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var simulationProperties = new dev.hugo.watcher.simulation.WatcherSimulationProperties();
        simulationProperties.setEnabled(false);
        var integrated = new MessageService(jdbc, manager, identities, properties, archiveModes,
                new DebounceService(jdbc, simulationProperties, manager), simulationProperties);

        assertEquals(MessageService.Acceptance.ACCEPTED,
                integrated.accept(message("shadow-disabled", "5511999999999@s.whatsapp.net", Map.of("conversation", "Oi"))));
        assertEquals(0, jdbc.queryForObject("select count(*) from simulation_batch_messages", Integer.class));
    }

    @Test void saturationDoesNotRollbackLegacyAndSnapshotRecoversAfterRestart() {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var limits = new WatcherSimulationProperties();
        limits.setMaxPendingBatches(1);
        var debounce = new DebounceService(jdbc, limits, manager);
        var integrated = new MessageService(jdbc, manager, identities, properties, archiveModes, debounce, limits);
        assertEquals(MessageService.Acceptance.ACCEPTED, integrated.accept(message("shadow-one", "one@s.whatsapp.net", Map.of("conversation", "one"))));
        debounce.claimReady(Instant.now());
        assertEquals(MessageService.Acceptance.ACCEPTED, integrated.accept(message("shadow-two", "two@s.whatsapp.net", Map.of("conversation", "two"))));
        debounce.claimReady(Instant.now());
        assertEquals(2, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message where shadow_payload is not null", Integer.class));
        jdbc.update("update simulation_batches set status='COMPLETED'");
        new DebounceService(jdbc, limits, manager).claimReady(Instant.now());
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_message where shadow_payload is not null", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from simulation_batch_messages", Integer.class));
    }

    @Test void shadowDatabaseFailureKeepsLegacyAndDurableRetry() {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var limits = new WatcherSimulationProperties();
        var debounce = new DebounceService(jdbc, limits, manager);
        var integrated = new MessageService(jdbc, manager, identities, properties, archiveModes, debounce, limits);
        jdbc.execute("alter table simulation_batch_messages rename to temporarily_unavailable_messages");
        try {
            assertEquals(MessageService.Acceptance.ACCEPTED, integrated.accept(message("shadow-db", "db@s.whatsapp.net", Map.of("conversation", "hello"))));
            debounce.claimReady(Instant.now());
            assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from processed_message where shadow_payload is not null", Integer.class));
        } finally {
            jdbc.execute("alter table temporarily_unavailable_messages rename to simulation_batch_messages");
        }
        debounce.claimReady(Instant.now());
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_batch_messages", Integer.class));
    }

    @Test void concurrentShadowWorkersDrainCapturedMessageOnce() throws Exception {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var limits = new WatcherSimulationProperties();
        var integrated = new MessageService(jdbc, manager, identities, properties, archiveModes, new DebounceService(jdbc,limits), limits);
        integrated.accept(message("concurrent-capture", "concurrent@s.whatsapp.net", Map.of("conversation","hello")));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var one = executor.submit(() -> { start.await(); return new DebounceService(jdbc,limits).claimReady(Instant.now()); });
            var two = executor.submit(() -> { start.await(); return new DebounceService(jdbc,limits).claimReady(Instant.now()); });
            start.countDown();
            one.get(10,java.util.concurrent.TimeUnit.SECONDS);
            two.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_batch_messages", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_message where shadow_payload is not null", Integer.class));
    }

    @Test void alreadyReadMessageNeverLeavesShadowCapture() {
        var manager = new DataSourceTransactionManager(jdbc.getDataSource());
        var integrated = new MessageService(jdbc, manager, identities, properties, archiveModes,
                new DebounceService(jdbc,new WatcherSimulationProperties()));
        String chat = "read@s.whatsapp.net";
        jdbc.update("insert into chat_snapshot(chat_jid,archived,synchronized_snapshot,last_read_at) values (?,false,true,now())", chat);
        assertEquals(MessageService.Acceptance.IGNORED,integrated.accept(messageAt("read-capture",chat,"2026-10-26T10:00:00Z")));
        assertEquals(0,jdbc.queryForObject("select count(*) from processed_message where shadow_payload is not null",Integer.class));
    }

    @Test void filteredModeRejectsUnknownOrUnsynchronizedArchiveState() {
        archiveModes.setMode(ArchiveMode.ARCHIVED_ONLY);
        var result = messages.accept(message("m-unknown-filtered", "5511999999999@s.whatsapp.net", Map.of("conversation", "Oi")));

        assertEquals(MessageService.Acceptance.QUARANTINED, result);
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from quarantined_events where source_event_id='m-unknown-filtered'", Integer.class));
    }

    @Test void unarchivedOnlyAcceptsOnlySynchronizedUnarchivedChats() {
        archiveModes.setMode(ArchiveMode.UNARCHIVED_ONLY);
        chats.apply(new ChatUpdate("5511999999998@s.whatsapp.net", Optional.of(false), Instant.parse("2026-10-26T09:00:00Z"),
                true, true, Optional.of("snapshot-unarchived"), Optional.of("Contato"), false, Map.of()));
        chats.apply(new ChatUpdate("5511999999997@s.whatsapp.net", Optional.of(true), Instant.parse("2026-10-26T09:00:00Z"),
                true, true, Optional.of("snapshot-archived"), Optional.of("Contato"), false, Map.of()));

        assertEquals(MessageService.Acceptance.ACCEPTED, messages.accept(message("m-unarchived", "5511999999998@s.whatsapp.net", Map.of("conversation", "Oi"))));
        assertEquals(MessageService.Acceptance.IGNORED, messages.accept(message("m-archived", "5511999999997@s.whatsapp.net", Map.of("conversation", "Oi"))));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void rejectsMissingExternalMessageIdWithoutFabricatingOne() {
        archive("5511999999999@s.whatsapp.net", "Contato");

        var result = messages.accept(new IncomingMessage(Optional.empty(), "5511999999999@s.whatsapp.net", Optional.empty(),
                Optional.empty(), Optional.empty(), false, "notify", false, Map.of("conversation", "Oi"), Instant.parse("2026-10-26T10:00:00Z")));

        assertEquals(MessageService.Acceptance.QUARANTINED, result);
        assertNull(jdbc.queryForObject("select source_event_id from quarantined_events limit 1", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    @Test void ownStatusAndProtocolMessagesAreIgnored() {
        archive("5511999999999@s.whatsapp.net", "Contato");

        assertEquals(MessageService.Acceptance.IGNORED, messages.accept(message("own", "5511999999999@s.whatsapp.net", Map.of("conversation", "own"), true)));
        assertEquals(MessageService.Acceptance.IGNORED, messages.accept(message("status", "status@broadcast", Map.of("conversation", "status"))));
        assertEquals(MessageService.Acceptance.IGNORED, messages.accept(message("protocol", "5511999999999@s.whatsapp.net", Map.of("protocolMessage", Map.of("type", 0)))));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
    }

    @Test void disabledGroupsAreIgnoredAndHistoryIsNotANewMessage() {
        properties.setIncludeGroups(false);
        archive("120363000000000000@g.us", "Equipe", true);

        assertEquals(MessageService.Acceptance.IGNORED, messages.accept(message("group", "120363000000000000@g.us", Map.of("conversation", "Oi"))));
        properties.setIncludeGroups(true);
        assertEquals(MessageService.Acceptance.IGNORED, messages.accept(message("history", "120363000000000000@g.us", Map.of("conversation", "Oi"), false, true)));
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    @Test void explicitIdentityAliasesShareMessageDeduplication() {
        archive("1001@lid", "Cliente");
        var first = new IncomingMessage(Optional.of("same-message"), "1001@lid", Optional.empty(),
                Optional.of("5511999999999@s.whatsapp.net"), Optional.empty(), false, "notify", false,
                Map.of("conversation", "Olá"), Instant.parse("2026-10-26T10:00:00Z"));
        var duplicate = new IncomingMessage(Optional.of("same-message"), "5511999999999@s.whatsapp.net", Optional.empty(),
                Optional.empty(), Optional.empty(), false, "notify", false,
                Map.of("conversation", "Olá"), Instant.parse("2026-10-26T10:00:01Z"));

        assertEquals(MessageService.Acceptance.ACCEPTED, messages.accept(first));
        assertEquals(MessageService.Acceptance.DUPLICATE, messages.accept(duplicate));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    @Test void blockedGroupsAndWorkOnlyPolicyArePreserved() {
        properties.setNeverNotifyGroupNames(java.util.List.of("233/234"));
        archive("120363000000000000@g.us", "233/234", true);
        archive("5511999999999@s.whatsapp.net", "Equipe JC");

        assertEquals(MessageService.Acceptance.IGNORED, messages.accept(message("blocked", "120363000000000000@g.us", Map.of("conversation", "Oi"))));
        assertEquals(MessageService.Acceptance.ACCEPTED, messages.accept(message("work", "5511999999999@s.whatsapp.net", Map.of("conversation", "Reunião"))));
        assertEquals("WORK_ONLY", jdbc.queryForObject("select payload->>'notificationPolicy' from pending_notification where chat_jid=?", String.class,
                "5511999999999@s.whatsapp.net"));
        assertEquals("Reunião", jdbc.queryForObject("select payload->>'preview' from pending_notification where chat_jid=?", String.class,
                "5511999999999@s.whatsapp.net"));
    }

    @Test void delayedMessageAtOrBeforeReadWatermarkDoesNotRecreatePendingNotification() {
        String jid = "5511999999999@s.whatsapp.net";
        archive(jid, "Contato");
        Instant readAt = Instant.parse("2026-10-26T10:05:00Z");
        assertEquals(0, chats.clearRead(jid, readAt, Optional.of(0), Optional.empty(), Optional.empty()));

        assertEquals(MessageService.Acceptance.IGNORED,
                messages.accept(messageAt("delayed-before-read", jid, "2026-10-26T10:04:59Z")));
        assertEquals(MessageService.Acceptance.IGNORED,
                messages.accept(messageAt("at-read-watermark", jid, "2026-10-26T10:05:00Z")));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    @Test void replayedMessageAfterChatAliasesMergeRemainsDeduplicated() {
        String lid = "1001@lid";
        String phone = "5511999999999@s.whatsapp.net";
        archive(lid, "Contato LID");
        archive(phone, "Contato telefone");
        assertEquals(MessageService.Acceptance.ACCEPTED,
                messages.accept(message("repeat-after-merge", lid, Map.of("conversation", "Oi"))));
        identities.observe(new IdentityObservation(phone, java.util.Set.of(), IdentityEvidence.NONE));

        identities.observe(new IdentityObservation(phone, java.util.Set.of(lid), IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR));

        assertEquals(MessageService.Acceptance.DUPLICATE,
                messages.accept(message("repeat-after-merge", phone, Map.of("conversation", "Oi"))));
        assertEquals(1, jdbc.queryForObject("select (payload->>'messageCount')::integer from pending_notification", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    @Test void usesTheConfiguredWorkOnlyNameRegex() {
        properties.setAllowedNameRegex("(?:JOB|WORK)");
        archive("5511999999999@s.whatsapp.net", "JOB Contact");

        assertEquals(MessageService.Acceptance.ACCEPTED, messages.accept(message("custom-regex", "5511999999999@s.whatsapp.net", Map.of("conversation", "Oi"))));
        assertEquals("WORK_ONLY", jdbc.queryForObject("select payload->>'notificationPolicy' from pending_notification", String.class));
    }

    @Test void groupChatAlternateCannotBeMistakenForParticipantAlternate() {
        String group = "120363000000000000@g.us";
        String participant = "1001@lid";
        String unrelatedChatAlias = "5511888888888@s.whatsapp.net";
        archive(group, "Equipe", true);
        var message = new IncomingMessage(Optional.of("group-message"), group, Optional.of(participant),
                Optional.of(unrelatedChatAlias), Optional.empty(), false, "notify", false,
                Map.of("conversation", "Oi"), Instant.parse("2026-10-26T10:00:00Z"));

        assertEquals(MessageService.Acceptance.ACCEPTED, messages.accept(message));
        assertEquals(1, jdbc.queryForObject("select count(*) from identity_aliases where alias=?", Integer.class, participant));
        assertEquals(0, jdbc.queryForObject("select count(*) from identity_aliases where alias=?", Integer.class, unrelatedChatAlias));
    }

    @Test void participantAliasesShareGroupMessageDeduplication() {
        String group = "120363000000000000@g.us";
        String lid = "1001@lid";
        String phone = "5511999999999@s.whatsapp.net";
        archive(group, "Equipe", true);
        var first = new IncomingMessage(Optional.of("same-group-message"), group, Optional.of(lid),
                Optional.empty(), Optional.of(phone), false, "notify", false,
                Map.of("conversation", "Oi"), Instant.parse("2026-10-26T10:00:00Z"));
        var duplicate = new IncomingMessage(Optional.of("same-group-message"), group, Optional.of(phone),
                Optional.empty(), Optional.empty(), false, "notify", false,
                Map.of("conversation", "Oi"), Instant.parse("2026-10-26T10:00:01Z"));

        assertEquals(MessageService.Acceptance.ACCEPTED, messages.accept(first));
        assertEquals(MessageService.Acceptance.DUPLICATE, messages.accept(duplicate));
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_message", Integer.class));
    }

    @Test void retainsNormalizedTextPreviewAndCapsItAtLegacyLimit() {
        archive("5511999999999@s.whatsapp.net", "Contato");
        String longText = " a  ".repeat(100);

        assertEquals(MessageService.Acceptance.ACCEPTED, messages.accept(message("preview", "5511999999999@s.whatsapp.net", Map.of("conversation", longText))));
        String preview = jdbc.queryForObject("select payload->>'preview' from pending_notification", String.class);
        assertNotNull(preview);
        assertTrue(preview.length() <= 140);
        assertFalse(preview.contains("  "));
    }

    private void archive(String jid, String name) { archive(jid, name, false); }
    private void archive(String jid, String name, boolean group) {
        chats.apply(new ChatUpdate(jid, Optional.of(true), Instant.parse("2026-10-26T09:00:00Z"), true, true,
                Optional.of("snapshot-" + jid), Optional.of(name), group, Map.of("synthetic", true)));
    }
    private static IncomingMessage message(String id, String jid, Map<String, Object> content) { return message(id, jid, content, false, false); }
    private static IncomingMessage message(String id, String jid, Map<String, Object> content, boolean fromMe) { return message(id, jid, content, fromMe, false); }
    private static IncomingMessage message(String id, String jid, Map<String, Object> content, boolean fromMe, boolean history) {
        return new IncomingMessage(Optional.of(id), jid, Optional.empty(), Optional.empty(), Optional.empty(), fromMe,
                "notify", history, content, Instant.parse("2026-10-26T10:00:00Z"));
    }
    private static IncomingMessage messageAt(String id, String jid, String at) {
        return new IncomingMessage(Optional.of(id), jid, Optional.empty(), Optional.empty(), Optional.empty(), false,
                "notify", false, Map.of("conversation", "atrasada"), Instant.parse(at));
    }
}
