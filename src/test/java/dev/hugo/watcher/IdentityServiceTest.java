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
        jdbc.update("delete from pending_notification");
        jdbc.update("delete from processed_message");
        jdbc.update("delete from quarantined_events");
        jdbc.update("delete from chat_snapshot");
        jdbc.update("delete from contact_identity");
        jdbc.update("delete from identity_aliases");
        identities = new IdentityService(jdbc, new DataSourceTransactionManager(dataSource));
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
}
