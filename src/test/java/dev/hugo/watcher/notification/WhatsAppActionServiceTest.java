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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        jdbc.update("delete from decision_delivery_attempts");
        jdbc.update("delete from decision_delivery_claims");
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
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at,real_action_allowed,decision_state,template_id) values (?,false,true,'synthetic_test',?,?,true,'WOULD_ACT','task4_test@v1')",
                decisionId, Timestamp.from(now), Timestamp.from(expiry));
        jdbc.update("update archive_monitor_settings set mode='ALL'");
        jdbc.update("update automation_settings set ai_classification_enabled=true");
        jdbc.update("delete from ai_data_consents");
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values ('15551234567@s.whatsapp.net','CONTACT_REQUEST','gemini',true,?)",Timestamp.from(expiry));
        jdbc.update("insert into simulation_ai_evaluations(batch_id,lease_token,expires_at,result) values (?,?,?,?::jsonb)",decisionId,UUID.randomUUID(),Timestamp.from(expiry),"{\"provider\": \"gemini\", \"purposes\": [\"CONTACT_REQUEST\"], \"available\": true, \"requestDetected\": true, \"status\": \"CLASSIFIED\", \"urgency\": \"NORMAL\", \"confidence\": 0.9}");
        jdbc.update("update simulation_decisions set production_mode=true,classification_status='CLASSIFIED',classification_urgency='NORMAL',classification_confidence=0.9 where batch_id=?",decisionId);
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
            sends.incrementAndGet(); return EvolutionSendResult.delivered();
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
            sends.incrementAndGet(); return EvolutionSendResult.unknown();
        }, Clock.fixed(now, ZoneOffset.UTC), true);
        dispatcher.dispatch(queued.actionId()); dispatcher.dispatch(queued.actionId());
        assertEquals(1, sends.get());
        assertEquals("UNKNOWN", status(queued.actionId()));
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
                (chat, body, key) -> EvolutionSendResult.delivered(), Clock.fixed(now, ZoneOffset.UTC), true);
        dispatcher.dispatch(queued.actionId());
        assertEquals("DELIVERED", status(queued.actionId()));
        assertEquals(Timestamp.from(now), jdbc.queryForObject("select last_real_action_at from decision_cooldowns where chat_jid=? and participant_jid=''", Timestamp.class, "15551234567@s.whatsapp.net"));
        UUID second = UUID.randomUUID();
        Instant expiry = now.plusSeconds(86_400);
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,?,'COMPLETED',?,?,?,?,?,?)",
                second, "15551234567@s.whatsapp.net", "participant-2", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiry), Timestamp.from(expiry));
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at,real_action_allowed,decision_state,template_id) values (?,false,true,'synthetic_test',?,?,true,'WOULD_ACT','task4_test@v1')",
                second, Timestamp.from(now), Timestamp.from(expiry));
        jdbc.update("update simulation_decisions set production_mode=true,classification_status='CLASSIFIED',classification_urgency='NORMAL',classification_confidence=0.9 where batch_id=?",second);
        jdbc.update("insert into simulation_ai_evaluations(batch_id,lease_token,expires_at,result) select ?,lease_token,expires_at,result from simulation_ai_evaluations where batch_id=?",second,decisionId);
        assertFalse(service(true).enqueue(second).accepted(), "conversation cooldown must apply across participants");
    }

    @Test void realSendFeatureIsOffByDefault() {
        assertFalse(service(false).enqueue(decisionId).accepted());
        assertEquals(0, actionCount());
    }

    @Test void expiresUnknownActionWithoutAllowingDecisionToBeQueuedAgain() {
        var actions = service(true);
        var queued = actions.enqueue(decisionId);
        var dispatcher = new WhatsAppActionDispatcher(jdbc, tx, actions,
                (chat, body, key) -> EvolutionSendResult.unknown(), Clock.fixed(now, ZoneOffset.UTC), true);
        dispatcher.dispatch(queued.actionId());
        jdbc.update("update whatsapp_action_outbox set created_at=?,updated_at=?,expires_at=? where action_id=?",
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.minusSeconds(60)), Timestamp.from(now), queued.actionId());

        assertEquals(1, dispatcher.purgeExpired(now));
        assertEquals(0, actionCount());
        assertFalse(actions.enqueue(decisionId).accepted());
        dispatcher.dispatch(queued.actionId());
        assertTrue(jdbc.queryForObject("select whatsapp_action_enqueued from simulation_decisions where batch_id=?", Boolean.class, decisionId));
    }

    @Test void retentionPurgesEveryActionStateIncludingUnknown() {
        Instant created = now.minusSeconds(7 * 86_400L);
        for (String state : java.util.List.of("PENDING", "DISPATCHED", "DELIVERED", "FAILED", "UNKNOWN", "SUPPRESSED")) {
            UUID actionId = UUID.randomUUID();
            jdbc.update("""
                    insert into whatsapp_action_outbox(action_id,decision_id,chat_jid,participant_jid,situation,
                        template_id,template_version,body,status,created_at,updated_at,expires_at)
                    values (?,?,?,?, 'CONTACT_REQUEST','task4_test',1,'Resposta automática: teste.',?,?,?,?)
                    """, actionId, decisionId, "retention-" + state + "@s.whatsapp.net", "",
                    state, Timestamp.from(created), Timestamp.from(created), Timestamp.from(now));
            var dispatcher = new WhatsAppActionDispatcher(jdbc, tx, service(true),
                    (chat, body, key) -> EvolutionSendResult.unknown(), Clock.fixed(now, ZoneOffset.UTC), true);
            assertEquals(1, dispatcher.purgeExpired(now), state);
            assertEquals(0, jdbc.queryForObject("select count(*) from whatsapp_action_outbox where action_id=?", Integer.class, actionId), state);
        }
    }

    @Test void concurrentEnqueueReturnsOnePersistedAction() throws Exception {
        var actions = service(true);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return actions.enqueue(decisionId); });
            var b = pool.submit(() -> { start.await(); return actions.enqueue(decisionId); });
            start.countDown();
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertTrue(first.accepted());
            assertTrue(second.accepted());
            assertEquals(first.actionId(), second.actionId());
            assertEquals(1, actionCount());
        }
    }

    @Test void concurrentDispatchSendsOnlyOnce() throws Exception {
        var actions = service(true);
        var queued = actions.enqueue(decisionId);
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch enteredTransport = new CountDownLatch(1);
        CountDownLatch releaseTransport = new CountDownLatch(1);
        var dispatcher = new WhatsAppActionDispatcher(jdbc, tx, actions, (chat, body, key) -> {
            sends.incrementAndGet(); enteredTransport.countDown();
            try { releaseTransport.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return EvolutionSendResult.unknown();
        }, Clock.fixed(now, ZoneOffset.UTC), true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> dispatcher.dispatch(queued.actionId()));
            assertTrue(enteredTransport.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> dispatcher.dispatch(queued.actionId()));
            second.get(5, TimeUnit.SECONDS);
            releaseTransport.countDown();
            first.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, sends.get());
        assertEquals("UNKNOWN", status(queued.actionId()));
    }

    @Test void engineReferenceBindsExactlyApprovedVersionDespiteNewerTemplate() {
        var situation = dev.hugo.watcher.policy.Situation.CONTACT_REQUEST;
        var approved = new dev.hugo.watcher.policy.ResponseTemplate("task4_test", 1, situation,
                "Resposta automática: retorno em breve.", true);
        var policy = new dev.hugo.watcher.policy.DecisionPolicy(true, false, true, false,
                java.util.Set.of(situation), java.util.Map.of(situation, approved),
                java.time.Duration.ZERO, java.time.Duration.ZERO, null, null);
        var decision = new dev.hugo.watcher.simulation.DecisionEngine().decide(
                new dev.hugo.watcher.simulation.Batch(decisionId, "15551234567@s.whatsapp.net", "", java.util.List.of()),
                dev.hugo.watcher.simulation.Classification.contactRequest(),
                new dev.hugo.watcher.policy.EffectivePolicy(java.util.Map.of(), null, policy), now);
        assertTrue(decision.wouldReply());
        assertTrue(decision.realActionAllowed());
        jdbc.update("update simulation_decisions set template_id=?,would_reply=?,would_alert=?,real_action_allowed=?,decision_state=? where batch_id=?",
                decision.templateReference(), decision.wouldReply(), decision.wouldAlert(), decision.realActionAllowed(), decision.status().name(), decisionId);
        jdbc.update("insert into response_templates(template_id,version,situation,body,identifies_automation,enabled) values ('task4_test',2,'CONTACT_REQUEST','Resposta automática: versão nova.',true,true) on conflict do nothing");

        var queued = service(true).enqueue(decisionId);
        assertTrue(queued.accepted());
        assertEquals(1, jdbc.queryForObject("select template_version from whatsapp_action_outbox where action_id=?", Integer.class, queued.actionId()));
        assertEquals(approved.body(), jdbc.queryForObject("select body from whatsapp_action_outbox where action_id=?", String.class, queued.actionId()));
        jdbc.update("update whatsapp_action_outbox set status='DISPATCHED' where action_id=?", queued.actionId());
        assertNotNull(service(true).revalidate(queued.actionId(), now));
        jdbc.update("delete from chat_automation_authorizations");
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CONTACT_REQUEST','task4_test',2)", "15551234567@s.whatsapp.net");
        assertNull(service(true).revalidate(queued.actionId(), now), "a newer approval cannot replace the original approved version");
    }

    @Test void auditExpiryDoesNotDeleteActionOrRollbackSensitivePurgeWithBacklog() {
        var queued = service(true).enqueue(decisionId);
        assertTrue(queued.accepted());
        // Audit TTL 1 day; action TTL 7 days. The action keeps its own evidence snapshot.
        Instant cutoff = now.plusSeconds(86_401);
        String inboxKey = "audit-expiry-" + decisionId;
        jdbc.update("insert into inbox_events(source,event_type,external_id,payload,received_at,expires_at) values ('evolution','messages.upsert',?,'{\"secret\":\"raw\"}',?,?)",
                inboxKey, Timestamp.from(now), Timestamp.from(now.plusSeconds(86_400)));
        jdbc.update("insert into simulation_batch_messages(batch_id,chat_jid,external_id,received_at,payload) values (?,?,'sensitive',?,'{\"secret\":\"snippet\"}')",
                decisionId, "15551234567@s.whatsapp.net", Timestamp.from(now));
        new dev.hugo.watcher.RetentionService(jdbc, tx).purge(cutoff);
        assertEquals(0, jdbc.queryForObject("select count(*) from simulation_decisions where batch_id=?", Integer.class, decisionId));
        assertEquals(0, jdbc.queryForObject("select count(*) from simulation_batch_messages where batch_id=?", Integer.class, decisionId));
        assertEquals("{}", jdbc.queryForObject("select payload::text from inbox_events where external_id=?", String.class, inboxKey));
        assertEquals(1, actionCount());
        jdbc.update("update whatsapp_action_outbox set status='DISPATCHED' where action_id=?", queued.actionId());
        assertNull(service(true).revalidate(queued.actionId(), cutoff));
        assertFalse(service(true).enqueue(decisionId).accepted());
        // More than one old chunk must not hold expired bodies or block the remaining purge.
        jdbc.update("insert into whatsapp_action_outbox(action_id,decision_id,chat_jid,situation,template_id,template_version,body,status,created_at,updated_at,expires_at) select gen_random_uuid(),gen_random_uuid(),'backlog-'||n,'CONTACT_REQUEST','task4_test',1,'Resposta automática: antiga.','DELIVERED',?,?,? from generate_series(1,1001) n",
                Timestamp.from(now.minusSeconds(1)), Timestamp.from(now), Timestamp.from(now.plusSeconds(7 * 86_400L - 1)));
        new dev.hugo.watcher.RetentionService(jdbc, tx).purge(now.plusSeconds(7 * 86_400L));
        assertEquals(0, actionCount());
    }

    private WhatsAppActionService service(boolean enabled) { return new WhatsAppActionService(jdbc, tx, Clock.fixed(now, ZoneOffset.UTC), enabled); }
    private int actionCount() { return jdbc.queryForObject("select count(*) from whatsapp_action_outbox", Integer.class); }
    private String status(UUID id) { return jdbc.queryForObject("select status from whatsapp_action_outbox where action_id=?", String.class, id); }
}
