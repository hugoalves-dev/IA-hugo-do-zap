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

import java.util.Set;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class IdentityServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private IdentityService identities;

    @BeforeEach void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batch_messages");
        jdbc.update("delete from simulation_batches");
        jdbc.update("delete from chat_automation_authorizations");
        jdbc.update("delete from decision_cooldowns");
        jdbc.update("delete from pending_notification");
        jdbc.update("delete from processed_message");
        jdbc.update("delete from quarantined_events");
        jdbc.update("delete from chat_snapshot");
        jdbc.update("delete from contact_identity");
        jdbc.update("delete from identity_aliases");
        identities = new IdentityService(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test void explicitMergePreservesAuthorizationsAndMostRecentCooldownAndRekeysBatches() {
        String lid = "1001@lid";
        String phone = "5511999999999@s.whatsapp.net";
        identities.observe(new IdentityObservation(lid, Set.of(), IdentityEvidence.NONE));
        identities.observe(new IdentityObservation(phone, Set.of(), IdentityEvidence.NONE));
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CONTACT_REQUEST','contact_request',1)", phone);
        jdbc.update("insert into decision_cooldowns(chat_jid,last_simulation_action_at,last_real_action_at) values (?,now()-interval '2 minutes',now()),(?,now(),now()-interval '2 minutes')", phone, lid);
        var debounce = new dev.hugo.watcher.simulation.DebounceService(jdbc, new dev.hugo.watcher.simulation.WatcherSimulationProperties());
        Instant now = Instant.now();
        for (String chat : java.util.List.of(lid,phone)) {
            debounce.enqueue(new IncomingMessage(java.util.Optional.of("same"),chat,java.util.Optional.empty(),java.util.Optional.empty(),java.util.Optional.empty(),false,"notify",false,java.util.Map.of("conversation","hello"),now),now);
        }
        identities.observe(new IdentityObservation(lid, Set.of(phone), IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR));
        assertEquals(1, jdbc.queryForObject("select count(*) from chat_automation_authorizations where chat_jid=?", Integer.class, lid));
        assertEquals(1, jdbc.queryForObject("select count(*) from decision_cooldowns where chat_jid=? and last_real_action_at>now()-interval '1 minute' and last_simulation_action_at>now()-interval '1 minute'", Integer.class,lid));
        assertEquals(0, jdbc.queryForObject("select count(*) from simulation_batches where chat_jid=?", Integer.class, phone));
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_batch_messages where chat_jid=?", Integer.class,lid));
    }

    @Test void doesNotJoinLidAndPhoneWithoutExplicitEvidence() {
        var lid = identities.observe(new IdentityObservation("1001@lid",
                Set.of("5511999999999@s.whatsapp.net"), IdentityEvidence.NONE));
        var phone = identities.observe(new IdentityObservation("5511999999999@s.whatsapp.net",
                Set.of(), IdentityEvidence.NONE));

        assertEquals("1001@lid", lid.canonicalJid());
        assertNotEquals(lid.canonicalJid(), phone.canonicalJid());
        assertEquals(Set.of("1001@lid"), lid.aliases());
        assertEquals(2, jdbc.queryForObject("select count(*) from identity_aliases", Integer.class));
    }

    @Test void joinsLidAndPhoneOnlyWhenTheObservationCarriesExplicitPairEvidence() {
        var linked = identities.observe(new IdentityObservation("1001@lid",
                Set.of("5511999999999@s.whatsapp.net"), IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR));
        var samePhone = identities.observe(new IdentityObservation("5511999999999@s.whatsapp.net",
                Set.of(), IdentityEvidence.NONE));

        assertEquals(linked.canonicalJid(), samePhone.canonicalJid());
        assertEquals(2, jdbc.queryForObject("select count(*) from identity_aliases", Integer.class));
    }

    @Test void differentGroupParticipantsRemainDifferentIdentities() {
        var firstSender = identities.observe(new IdentityObservation("1001@lid", Set.of(), IdentityEvidence.NONE));
        var secondSender = identities.observe(new IdentityObservation("1002@lid", Set.of(), IdentityEvidence.NONE));

        assertNotEquals(firstSender.canonicalJid(), secondSender.canonicalJid());
    }

    @Test void explicitAliasMergeMovesAndCombinesSnapshotsAndPendingNotifications() {
        String phone = "5511999999999@s.whatsapp.net";
        String lid = "1001@lid";
        Instant first = Instant.parse("2026-10-26T10:00:00Z");
        Instant last = Instant.parse("2026-10-26T10:04:00Z");
        jdbc.update("insert into chat_snapshot(chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot) values (?,true,?,'phone',false,true)",
                phone, Timestamp.from(first));
        jdbc.update("insert into chat_snapshot(chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot) values (?,true,?,'lid',false,true)",
                lid, Timestamp.from(last));
        insertPending(phone, 2, "2026-10-26T10:00:00Z", "2026-10-26T10:01:00Z", "primeira");
        insertPending(lid, 3, "2026-10-26T10:02:00Z", "2026-10-26T10:04:00Z", "última");

        var merged = identities.observe(new IdentityObservation(lid, Set.of(phone), IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR));

        assertEquals(1, jdbc.queryForObject("select count(*) from chat_snapshot", Integer.class));
        assertEquals(merged.canonicalJid(), jdbc.queryForObject("select chat_jid from chat_snapshot", String.class));
        assertEquals(last, jdbc.queryForObject("select snapshot_at from chat_snapshot where chat_jid=?", Timestamp.class,
                merged.canonicalJid()).toInstant());
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(5, jdbc.queryForObject("select (payload->>'messageCount')::integer from pending_notification where chat_jid=?", Integer.class,
                merged.canonicalJid()));
        assertEquals("2026-10-26T10:00:00Z", jdbc.queryForObject("select payload->>'firstMessageAt' from pending_notification where chat_jid=?", String.class,
                merged.canonicalJid()));
        assertEquals("2026-10-26T10:04:00Z", jdbc.queryForObject("select payload->>'lastMessageAt' from pending_notification where chat_jid=?", String.class,
                merged.canonicalJid()));
        assertEquals("última", jdbc.queryForObject("select payload->>'preview' from pending_notification where chat_jid=?", String.class,
                merged.canonicalJid()));
    }

    @Test void aliasMergeDropsReadCoveredPendingAndDoesNotCreateDeliverableQueueWithoutKnownSnapshot() {
        String phone = "5511999999999@s.whatsapp.net";
        String lid = "1001@lid";
        Instant t = Instant.parse("2026-10-26T10:00:00Z");
        jdbc.update("insert into chat_snapshot(chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot,last_read_at) values (?,true,?,'phone',false,true,?)",
                phone, Timestamp.from(t), Timestamp.from(Instant.parse("2026-10-26T10:03:00Z")));
        jdbc.update("insert into chat_snapshot(chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot) values (?,true,?,'lid',false,true)",
                lid, Timestamp.from(t.plusSeconds(1)));
        insertPending(phone, 2, "2026-10-26T10:00:00Z", "2026-10-26T10:02:00Z", "lida");
        insertPending(lid, 1, "2026-10-26T10:04:00Z", "2026-10-26T10:04:00Z", "nova");

        var merged = identities.observe(new IdentityObservation(lid, Set.of(phone), IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR));

        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(1, jdbc.queryForObject("select (payload->>'messageCount')::integer from pending_notification where chat_jid=?", Integer.class, merged.canonicalJid()));
        assertEquals("nova", jdbc.queryForObject("select payload->>'preview' from pending_notification where chat_jid=?", String.class, merged.canonicalJid()));
        assertEquals(Instant.parse("2026-10-26T10:03:00Z"), jdbc.queryForObject(
                "select last_read_at from chat_snapshot where chat_jid=?", Timestamp.class, merged.canonicalJid()).toInstant());

        jdbc.update("delete from chat_snapshot");
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batch_messages");
        jdbc.update("delete from simulation_batches");
        jdbc.update("delete from chat_automation_authorizations");
        jdbc.update("delete from decision_cooldowns");
        jdbc.update("delete from pending_notification");
        jdbc.update("delete from identity_aliases");
        String unknownPhone = "5511888888888@s.whatsapp.net";
        insertPending(unknownPhone, 1, "2026-10-26T10:05:00Z", "2026-10-26T10:05:00Z", "sem estado");
        identities.observe(new IdentityObservation("2001@lid", Set.of(unknownPhone), IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR));
        assertEquals(0, jdbc.queryForObject("select count(*) from pending_notification", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from quarantined_events where event_kind='PENDING_NOTIFICATION' and source_event_id is null and reason='alias_merge_state_unknown'", Integer.class));
        assertTrue(jdbc.queryForObject("select sensitive_expires_at <= received_at + interval '7 days' from quarantined_events where reason='alias_merge_state_unknown'", Boolean.class));
    }

    @Test void mergedQueueKeepsLatestSourceExpirationAndScrubsPreviewAtItsOwnSevenDayDeadline() {
        String phone = "5511999999999@s.whatsapp.net";
        String lid = "1001@lid";
        Instant created = Instant.parse("2026-10-20T10:00:00Z");
        Instant shortExpiry = Instant.parse("2026-10-21T10:00:00Z");
        Instant longExpiry = Instant.parse("2026-10-27T10:00:00Z");
        jdbc.update("insert into chat_snapshot(chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot) values (?,true,?,'phone',false,true)",
                phone, Timestamp.from(created));
        jdbc.update("insert into chat_snapshot(chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot) values (?,true,?,'lid',false,true)",
                lid, Timestamp.from(created.plusSeconds(1)));
        insertPendingWithExpiry(phone, created, longExpiry, 1, "2026-10-20T10:00:00Z", "2026-10-20T10:00:00Z", "antiga");
        insertPendingWithExpiry(lid, shortExpiry.minusSeconds(1), shortExpiry, 1, "2026-10-21T09:59:59Z", "2026-10-21T09:59:59Z", "mais recente");

        var merged = identities.observe(new IdentityObservation(lid, Set.of(phone), IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR));

        assertEquals(longExpiry, jdbc.queryForObject("select expires_at from pending_notification where chat_jid=?", Timestamp.class, merged.canonicalJid()).toInstant());
        assertEquals(shortExpiry.toString(), jdbc.queryForObject("select payload->>'previewExpiresAt' from pending_notification where chat_jid=?", String.class, merged.canonicalJid()));
        new SensitiveDataRetentionService(jdbc).purge(shortExpiry);
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where chat_jid=?", Integer.class, merged.canonicalJid()));
        assertNull(jdbc.queryForObject("select payload->>'preview' from pending_notification where chat_jid=?", String.class, merged.canonicalJid()));
        assertEquals(1, jdbc.queryForObject("select count(*) from pending_notification where expires_at=?", Integer.class, Timestamp.from(longExpiry)));
    }

    @Test void contactDisplayNamesFollowLegacySourcePrecedenceAndRejectTechnicalNames() {
        String jid = "5511999999999@s.whatsapp.net";
        identities.observeContactName(jid, "Nome do chat", ContactNameSource.CHAT);
        identities.observeContactName(jid, "Nome verificado", ContactNameSource.VERIFIED);
        identities.observeContactName(jid, "Nome da agenda", ContactNameSource.CONTACT_NAME);
        identities.observeContactName(jid, "Nome notify", ContactNameSource.NOTIFY);
        identities.observeContactName(jid, "5511999999999@s.whatsapp.net", ContactNameSource.CONTACT_NAME);

        assertEquals("Nome da agenda", identities.resolveDisplayName(jid));
        assertEquals("CONTACT_NAME", jdbc.queryForObject("select display_name_source from contact_identity where canonical_jid=?", String.class, jid));
    }

    private void insertPending(String jid, int count, String first, String last, String preview) {
        String payload = "{\"messageCount\":" + count + ",\"firstMessageAt\":\"" + first
                + "\",\"lastMessageAt\":\"" + last + "\",\"preview\":\"" + preview
                + "\",\"isGroup\":false,\"notificationPolicy\":\"ANYTIME\"}";
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at) values (?,?::jsonb,?)", jid, payload,
                Timestamp.from(Instant.parse(last)));
    }

    private void insertPendingWithExpiry(String jid, Instant updatedAt, Instant expiresAt, int count, String first,
                                         String last, String preview) {
        String payload = "{\"messageCount\":" + count + ",\"firstMessageAt\":\"" + first
                + "\",\"lastMessageAt\":\"" + last + "\",\"preview\":\"" + preview
                + "\",\"isGroup\":false,\"notificationPolicy\":\"ANYTIME\"}";
        jdbc.update("insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at) values (?,?::jsonb,?,?,?)",
                jid, payload, Timestamp.from(updatedAt), Timestamp.from(updatedAt), Timestamp.from(expiresAt));
    }
}
