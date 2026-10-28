package dev.hugo.watcher.notification;

import dev.hugo.watcher.NotificationOutboxRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class IntelligentAlertServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private UUID decisionId;
    private Instant now;
    private IntelligentAlertService alerts;
    private static final String OWNER_JID = "15550000000@s.whatsapp.net";

    @BeforeEach void migrateAndSeed() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        var tx = new DataSourceTransactionManager(source);
        jdbc.update("delete from decision_delivery_attempts");
        jdbc.update("delete from decision_delivery_claims");
        jdbc.update("delete from outbox_actions");
        jdbc.update("delete from chat_automation_authorizations");
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batch_messages");
        jdbc.update("delete from simulation_batches");
        jdbc.update("update automation_settings set ai_classification_enabled=true,intelligent_alerts_enabled=true,kill_switch_enabled=false where singleton_id=1");
        jdbc.update("insert into response_templates(template_id,version,situation,body,identifies_automation,enabled) values ('alert_test',1,'CRITICAL_URGENCY','Resposta automática: evento crítico.',true,true) on conflict(template_id,version) do update set body=excluded.body,identifies_automation=true,enabled=true");
        now = Instant.parse("2026-10-27T12:00:00Z");
        Instant expiry = now.plusSeconds(86_400);
        decisionId = UUID.randomUUID();
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,?,'COMPLETED',?,?,?,?,?,?)",
                decisionId, "15551234567@s.whatsapp.net", "", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiry), Timestamp.from(expiry));
        jdbc.update("insert into simulation_batch_messages(batch_id,chat_jid,participant_jid,external_id,received_at,payload) values (?,?,?,?,?,?::jsonb)",
                decisionId, "15551234567@s.whatsapp.net", "", "synthetic-source-id", Timestamp.from(now),
                "{\"fromMe\":false,\"chatJid\":\"15551234567@s.whatsapp.net\",\"content\":{\"extendedTextMessage\":{\"text\":\"RAW_SECRET_MUST_NOT_ESCAPE\",\"contextInfo\":{\"mentionedJid\":[\"" + OWNER_JID + "\"]}}}}");
        jdbc.update("""
                insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at,
                    real_action_allowed,decision_state,template_id,classification_status,classification_urgency,
                    classification_confidence,intelligent_alert_reference_hash)
                values (?,true,false,'synthetic_audit',?,?,true,'WOULD_ACT','alert_test','CLASSIFIED','CRITICAL',0.98,?)
                """, decisionId, Timestamp.from(now), Timestamp.from(expiry), hash("synthetic-source-id"));
        jdbc.update("update archive_monitor_settings set mode='ALL'");
        jdbc.update("update automation_settings set ai_classification_enabled=true");
        jdbc.update("delete from ai_data_consents");
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values ('15551234567@s.whatsapp.net','CONTACT_REQUEST','gemini',true,?)",Timestamp.from(expiry));
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values ('15551234567@s.whatsapp.net','CRITICAL_URGENCY','gemini',true,?)",Timestamp.from(expiry));
        jdbc.update("insert into simulation_ai_evaluations(batch_id,lease_token,expires_at,result) values (?,?,?,?::jsonb)",decisionId,UUID.randomUUID(),Timestamp.from(expiry),"{\"provider\": \"gemini\", \"purposes\": [\"CONTACT_REQUEST\", \"CRITICAL_URGENCY\"], \"available\": true, \"requestDetected\": true, \"status\": \"CLASSIFIED\", \"urgency\": \"CRITICAL\", \"confidence\": 0.98}");
        jdbc.update("update simulation_decisions set alert_template_reference='alert_test@v1',production_mode=true,classification_status='CLASSIFIED',classification_urgency='CRITICAL',classification_confidence=0.98 where batch_id=?",decisionId);
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CRITICAL_URGENCY','alert_test',1)", "15551234567@s.whatsapp.net");
        alerts = new IntelligentAlertService(jdbc, tx, new NotificationOutboxRepository(jdbc, tx),
                Clock.fixed(now, ZoneOffset.UTC), true, OWNER_JID);
    }

    @Test void enqueuesOnlyAggregateAlertWithUrgencyAndPersistedObjectiveReference() {
        var result = alerts.enqueue(decisionId);
        assertTrue(result.accepted());
        assertFalse(result.action().payload().contains("RAW_SECRET_MUST_NOT_ESCAPE"));
        assertFalse(result.action().payload().contains("15551234567"));
        assertTrue(result.action().payload().contains("CRITICAL"));
        assertTrue(result.action().payload().contains(decisionId.toString()));
        assertEquals("intelligent_alert", result.action().actionType());
        assertTrue(alerts.enqueue(decisionId).accepted());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_actions where action_type='intelligent_alert'", Integer.class));
    }

    @Test void uncertainClassificationMissingReferenceAndRevokedAuthorizationAreBlocked() {
        jdbc.update("update simulation_decisions set classification_status='UNCERTAIN' where batch_id=?", decisionId);
        assertFalse(alerts.enqueue(decisionId).accepted());
        jdbc.update("update simulation_decisions set classification_status='CLASSIFIED',intelligent_alert_reference_hash=null where batch_id=?", decisionId);
        assertFalse(alerts.enqueue(decisionId).accepted());
        jdbc.update("update simulation_decisions set intelligent_alert_reference_hash=? where batch_id=?", hash("missing-source"), decisionId);
        assertFalse(alerts.enqueue(decisionId).accepted());
        jdbc.update("update simulation_decisions set intelligent_alert_reference_hash=? where batch_id=?", hash("synthetic-source-id"), decisionId);
        jdbc.update("delete from chat_automation_authorizations");
        assertFalse(alerts.enqueue(decisionId).accepted());
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CRITICAL_URGENCY','alert_test',1)", "15551234567@s.whatsapp.net");
        jdbc.update("update automation_settings set kill_switch_enabled=true where singleton_id=1");
        assertFalse(alerts.enqueue(decisionId).accepted());
    }

    @Test void alertGenerationIsOffByDefault() {
        var disabled = new IntelligentAlertService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()),
                new NotificationOutboxRepository(jdbc, new DataSourceTransactionManager(jdbc.getDataSource())),
                Clock.fixed(now, ZoneOffset.UTC), false, OWNER_JID);
        assertFalse(disabled.enqueue(decisionId).accepted());
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_actions where action_type='intelligent_alert'", Integer.class));
    }

    @Test void dispatcherRevalidationBlocksRevokedSwitchOrOptIn() {
        var action = alerts.enqueue(decisionId).action();
        jdbc.update("update outbox_actions set status='PROCESSING' where id=?", action.id());
        assertTrue(alerts.revalidate(action, now));
        jdbc.update("update automation_settings set kill_switch_enabled=true where singleton_id=1");
        assertFalse(alerts.revalidate(action, now));
    }

    @Test void rejectsModelUrgencyWithoutStructuredPersonalMentionOrOwnReply() {
        jdbc.update("update simulation_batch_messages set payload=?::jsonb where batch_id=?",
                "{\"fromMe\":false,\"chatJid\":\"15551234567@s.whatsapp.net\",\"content\":{\"extendedTextMessage\":{\"text\":\"RAW\",\"contextInfo\":{\"mentionedJid\":[\"15559999999@s.whatsapp.net\"]}}}}", decisionId);
        assertFalse(alerts.enqueue(decisionId).accepted());
        jdbc.update("update simulation_batch_messages set payload=?::jsonb where batch_id=?",
                "{\"fromMe\":false,\"chatJid\":\"15551234567@s.whatsapp.net\",\"content\":{\"conversation\":\"RAW\"}}", decisionId);
        assertFalse(alerts.enqueue(decisionId).accepted());
    }

    @Test void acceptsStructuredReplyOnlyWhenItReferencesAnIndexedOwnerMessage() {
        jdbc.update("update simulation_batch_messages set payload=?::jsonb where batch_id=?",
                "{\"fromMe\":false,\"chatJid\":\"15551234567@s.whatsapp.net\",\"content\":{\"extendedTextMessage\":{\"text\":\"RAW\",\"contextInfo\":{\"stanzaId\":\"owner-outbound-1\"}}}}", decisionId);
        assertFalse(alerts.enqueue(decisionId).accepted());
        jdbc.update("insert into whatsapp_owner_message_references(chat_jid,external_id,observed_at,expires_at) values (?,?,?,?)",
                "15551234567@s.whatsapp.net", "owner-outbound-1", Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(60)));
        assertTrue(alerts.enqueue(decisionId).accepted());
    }

    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
