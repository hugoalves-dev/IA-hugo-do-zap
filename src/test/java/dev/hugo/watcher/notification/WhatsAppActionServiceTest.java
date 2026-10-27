package dev.hugo.watcher.notification;

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
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WhatsAppActionServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private final Instant now = Instant.parse("2026-10-27T12:00:00Z");
    private UUID decisionId;

    @BeforeEach void migrateAndSeed() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        tx = new DataSourceTransactionManager(source);
        jdbc.update("delete from whatsapp_action_outbox");
        jdbc.update("delete from chat_automation_authorizations");
        jdbc.update("delete from decision_cooldowns");
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batches");
        jdbc.update("insert into response_templates(template_id,version,situation,body,identifies_automation,enabled) values ('task4_test',1,'CONTACT_REQUEST','Resposta automática: retorno em breve.',true,true) on conflict(template_id,version) do update set enabled=true,body=excluded.body,identifies_automation=true");
        jdbc.update("update automation_settings set intelligent_alerts_enabled=true,auto_reply_enabled=true,kill_switch_enabled=false where singleton_id=1");
        decisionId = UUID.randomUUID();
        Instant expiry = now.plusSeconds(86_400);
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,?,'COMPLETED',?,?,?,?,?,?)",
                decisionId, "15551234567@s.whatsapp.net", "", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiry), Timestamp.from(expiry));
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at,real_action_allowed,decision_state,template_id) values (?,false,true,'synthetic_test',?,?,true,'WOULD_ACT','task4_test')",
                decisionId, Timestamp.from(now), Timestamp.from(expiry));
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CONTACT_REQUEST','task4_test',1)", "15551234567@s.whatsapp.net");
    }

    @Test void refusesShadowDecisionAndDoesNotCreateAction() {
        jdbc.update("update simulation_decisions set real_action_allowed=false where batch_id=?", decisionId);
        assertFalse(service(true).enqueue(decisionId).accepted());
        assertEquals(0, actionCount());
    }

    @Test void requiresConversationOptInAndRespectsKillSwitch() {
        jdbc.update("delete from chat_automation_authorizations");
        assertFalse(service(true).enqueue(decisionId).accepted());
        assertEquals(0, actionCount());
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CONTACT_REQUEST','task4_test',1)", "15551234567@s.whatsapp.net");
        jdbc.update("update automation_settings set kill_switch_enabled=true where singleton_id=1");
        assertFalse(service(true).enqueue(decisionId).accepted());
        assertEquals(0, actionCount());
    }

    @Test void duplicateDecisionCreatesAtMostOneActionAndRevocationBeforeSendSuppresses() {
        var actions = service(true);
        var first = actions.enqueue(decisionId);
        var duplicate = actions.enqueue(decisionId);
        assertTrue(first.accepted());
        assertEquals(first.actionId(), duplicate.actionId());
        assertEquals(1, actionCount());
        jdbc.update("delete from chat_automation_authorizations");
        AtomicInteger sends = new AtomicInteger();
        var dispatcher = new WhatsAppActionDispatcher(jdbc, tx, actions, (chat, body, key) -> {
            sends.incrementAndGet(); return EvolutionSendResult.delivered("fake-message");
        }, Clock.fixed(now, ZoneOffset.UTC), true);
        dispatcher.dispatch(first.actionId());
        assertEquals(0, sends.get());
        assertEquals("SUPPRESSED", status(first.actionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from decision_cooldowns where last_real_action_at is not null", Integer.class));
    }

    @Test void ambiguousTimeoutIsNotRetriedAndDoesNotAdvanceRealCooldown() {
        var queued = service(true).enqueue(decisionId);
        AtomicInteger sends = new AtomicInteger();
        var dispatcher = new WhatsAppActionDispatcher(jdbc, tx, service(true), (chat, body, key) -> {
            sends.incrementAndGet(); return EvolutionSendResult.uncertain();
        }, Clock.fixed(now, ZoneOffset.UTC), true);
        dispatcher.dispatch(queued.actionId()); dispatcher.dispatch(queued.actionId());
        assertEquals(1, sends.get());
        assertEquals("UNCERTAIN", status(queued.actionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from decision_cooldowns where last_real_action_at is not null", Integer.class));
    }

    @Test void confirmedFailureDoesNotAdvanceRealCooldown() {
        var queued = service(true).enqueue(decisionId);
        var dispatcher = new WhatsAppActionDispatcher(jdbc, tx, service(true),
                (chat, body, key) -> EvolutionSendResult.failed(), Clock.fixed(now, ZoneOffset.UTC), true);
        dispatcher.dispatch(queued.actionId());
        dispatcher.dispatch(queued.actionId());
        assertEquals("FAILED", status(queued.actionId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from decision_cooldowns where last_real_action_at is not null", Integer.class));
    }

    @Test void confirmedDeliveryAdvancesRealCooldown() {
        var queued = service(true).enqueue(decisionId);
        var dispatcher = new WhatsAppActionDispatcher(jdbc, tx, service(true),
                (chat, body, key) -> EvolutionSendResult.delivered("fake-message"), Clock.fixed(now, ZoneOffset.UTC), true);
        dispatcher.dispatch(queued.actionId());
        assertEquals("DELIVERED", status(queued.actionId()));
        assertEquals(Timestamp.from(now), jdbc.queryForObject("select last_real_action_at from decision_cooldowns where chat_jid=? and participant_jid=''", Timestamp.class, "15551234567@s.whatsapp.net"));
        UUID second = UUID.randomUUID();
        Instant expiry = now.plusSeconds(86_400);
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,?,'COMPLETED',?,?,?,?,?,?)",
                second, "15551234567@s.whatsapp.net", "participant-2", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiry), Timestamp.from(expiry));
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at,real_action_allowed,decision_state,template_id) values (?,false,true,'synthetic_test',?,?,true,'WOULD_ACT','task4_test')",
                second, Timestamp.from(now), Timestamp.from(expiry));
        assertFalse(service(true).enqueue(second).accepted(), "conversation cooldown must apply across participants");
    }

    @Test void realSendFeatureIsOffByDefault() {
        assertFalse(service(false).enqueue(decisionId).accepted());
        assertEquals(0, actionCount());
    }

    private WhatsAppActionService service(boolean enabled) { return new WhatsAppActionService(jdbc, tx, Clock.fixed(now, ZoneOffset.UTC), enabled); }
    private int actionCount() { return jdbc.queryForObject("select count(*) from whatsapp_action_outbox", Integer.class); }
    private String status(UUID id) { return jdbc.queryForObject("select status from whatsapp_action_outbox where action_id=?", String.class, id); }
}
