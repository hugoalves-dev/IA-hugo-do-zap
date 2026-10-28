package dev.hugo.watcher.ai;

import dev.hugo.watcher.IncomingMessage;
import dev.hugo.watcher.WatcherProperties;
import dev.hugo.watcher.policy.*;
import dev.hugo.watcher.simulation.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class AiSimulationAcceptanceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    BudgetService budgets;
    final Clock clock = Clock.systemUTC();
    final String chat = "consenting@s.whatsapp.net";
    AtomicInteger calls;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        budgets = new BudgetService(jdbc, manager, new SimpleMeterRegistry());
        jdbc.update("delete from outbox_actions");
        jdbc.update("delete from whatsapp_action_outbox");
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from simulation_batches");
        jdbc.update("delete from chat_automation_authorizations");
        jdbc.update("delete from decision_cooldowns");
        jdbc.update("delete from availability_overrides");
        jdbc.update("delete from ai_data_consents");
        jdbc.update("delete from ai_budget_reservations");
        jdbc.update("delete from ai_budget_limits");
        jdbc.update("insert into ai_budget_limits(singleton_id,daily_limit_micros,monthly_limit_micros) values (1,100,100)");
        jdbc.update("update archive_monitor_settings set mode='ALL'");
        jdbc.update("update automation_settings set ai_classification_enabled=true,kill_switch_enabled=false,auto_reply_enabled=false,intelligent_alerts_enabled=false");
        consent("gemini"); consent("openrouter");
        calls = new AtomicInteger();
    }
    @Test void defaultOffAndKillSwitchPreventAllProviderCalls() {
        var batch = batch();
        assertFalse(classifier(false, fake()).classify(batch).available());
        jdbc.update("update automation_settings set kill_switch_enabled=true");
        assertFalse(classifier(true, fake()).classify(batch).available());
        assertEquals(0, calls.get());
    }
    @Test void simulationClassifiesOutsideSqlTransactionAndReusesDurableResult() {
        jdbc.update("update automation_settings set auto_reply_enabled=true,intelligent_alerts_enabled=true");
        jdbc.update("update response_templates set body='Resposta automática: retorno em breve.',enabled=true where template_id='contact_request'");
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CONTACT_REQUEST','contact_request',1)",chat);
        var ai = classifier(true, fake());
        var service = new SimulationService(jdbc, manager, clock, new WatcherSimulationProperties(), new DecisionEngine(),
                new PersistedPolicyRepository(jdbc, new PolicyResolver()), new AvailabilityService(new HolidayService(jdbc)), new WatcherProperties(), ai);
        var batch = batch();
        var decision = service.evaluate(batch);
        assertTrue(decision.wouldReply(),decision.toString()); assertTrue(decision.wouldAlert(),decision.toString());
        service.evaluate(batch);
        assertEquals(1, calls.get());
        assertEquals("UNCERTAIN", jdbc.queryForObject("select status from ai_budget_reservations", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from whatsapp_action_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_actions where action_type='intelligent_alert'", Integer.class));
    }
    @Test void completeSimulationDecisionCanBindOnlyItsApprovedTemplateVersion() {
        jdbc.update("update automation_settings set auto_reply_enabled=true,intelligent_alerts_enabled=false");
        jdbc.update("update response_templates set body='Resposta automática: aprovada.',enabled=true where template_id='contact_request' and version=1");
        jdbc.update("insert into response_templates(template_id,version,situation,body,identifies_automation,enabled) values ('contact_request',2,'CONTACT_REQUEST','Resposta automática: nova.',true,true) on conflict do nothing");
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CONTACT_REQUEST','contact_request',1)", chat);
        var service = new SimulationService(jdbc, manager, clock, new WatcherSimulationProperties(), new DecisionEngine(),
                new PersistedPolicyRepository(jdbc, new PolicyResolver()), new AvailabilityService(new HolidayService(jdbc)), new WatcherProperties(), classifier(true, fake()));
        var batch = batch();
        var decision = service.evaluate(batch);
        assertTrue(decision.realActionAllowed(), decision.toString());
        assertTrue(decision.wouldReply());
        assertFalse(decision.wouldAlert());
        assertEquals("contact_request@v1", decision.templateId());
        assertEquals(0, jdbc.queryForObject("select count(*) from whatsapp_action_outbox", Integer.class), "simulation itself must remain shadow");
        var queued = new dev.hugo.watcher.notification.WhatsAppActionService(jdbc, manager, clock, true).enqueue(batch.id());
        assertFalse(queued.accepted(), "shadow audit cannot authorize real enqueue");
        assertEquals(0,jdbc.queryForObject("select count(*) from whatsapp_action_outbox",Integer.class));
        assertEquals("CLASSIFIED", jdbc.queryForObject("select classification_status from simulation_decisions where batch_id=?", String.class, batch.id()));
        assertNull(jdbc.queryForObject("select intelligent_alert_reference_hash from simulation_decisions where batch_id=?", String.class, batch.id()));
    }

    @Test void privacyAndUnknownArchivedStateBlockBeforeHttp() {
        jdbc.update("delete from ai_data_consents");
        assertFalse(classifier(true, fake()).classify(batch()).available());
        consent("gemini");
        jdbc.update("update archive_monitor_settings set mode='ARCHIVED_ONLY'");
        assertFalse(classifier(true, fake()).classify(batch()).available());
        assertEquals(0, calls.get());
    }
    @Test void schemaRetryAndTechnicalFallbackHaveSeparateReservations() {
        ProviderTransport fake = (request, timeout, max) -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            int count = calls.incrementAndGet();
            assertEquals(count, jdbc.queryForObject("select count(*) from ai_budget_reservations", Integer.class));
            if (count == 1) return gemini("{}");
            if (count == 2) return new ProviderTransport.Response(503, "{}");
            return new ProviderTransport.Response(200, "{\"choices\":[{\"message\":{\"content\":" + quote(valid()) + "}}],\"usage\":{\"cost\":0.000004}}");
        };
        assertTrue(classifier(true, fake).classify(batch()).available());
        assertEquals(3, calls.get());
        assertEquals(2, jdbc.queryForObject("select count(*) from ai_budget_reservations where status='UNCERTAIN'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from ai_budget_reservations where status='SETTLED'", Integer.class));
    }
    @Test void revokedConsentBetweenSchemaAttemptsBlocksRetry() {
        ProviderTransport fake = (request, timeout, max) -> {
            calls.incrementAndGet();
            jdbc.update("delete from ai_data_consents");
            return gemini("{}");
        };
        assertFalse(classifier(true, fake).classify(batch()).available());
        assertEquals(1, calls.get());
    }
    @Test void killSwitchBetweenSchemaAttemptsBlocksRetryAndDiscardsResult() {
        var ai = classifier(true,(request,timeout,max) -> {
            calls.incrementAndGet(); jdbc.update("update automation_settings set kill_switch_enabled=true"); return gemini("{}");
        });
        assertFalse(ai.classify(batch()).available()); assertEquals(1,calls.get());
        assertEquals("UNCERTAIN",jdbc.queryForObject("select status from ai_budget_reservations",String.class));
    }
    @Test void expiredLeaseCannotPublishLateProviderResult() {
        var batch = batch();
        var ai = classifier(true,(request,timeout,max) -> {
            calls.incrementAndGet();
            jdbc.update("update simulation_ai_evaluations set expires_at=now()-interval '1 second' where batch_id=?",batch.id());
            return gemini(valid());
        });
        assertFalse(ai.classify(batch).available()); assertFalse(ai.classify(batch).available());
        assertEquals(1,calls.get());
        assertNull(jdbc.queryForObject("select result::text from simulation_ai_evaluations where batch_id=?",String.class,batch.id()));
    }
    @Test void decisionPersistenceFailureRecoversWithoutAnotherPaidAttempt() {
        var ai = classifier(true,fake());
        var service = new SimulationService(jdbc,manager,clock,new WatcherSimulationProperties(),new DecisionEngine(),
                new PersistedPolicyRepository(jdbc,new PolicyResolver()),new AvailabilityService(new HolidayService(jdbc)),new WatcherProperties(),ai);
        var batch = batch();
        jdbc.execute("create function reject_test_decision() returns trigger language plpgsql as $$ begin raise exception 'test persistence failure'; end $$");
        jdbc.execute("create trigger reject_test_decision before insert on simulation_decisions for each row execute function reject_test_decision()");
        try { assertThrows(RuntimeException.class,() -> service.evaluate(batch)); }
        finally { jdbc.execute("drop trigger reject_test_decision on simulation_decisions"); jdbc.execute("drop function reject_test_decision()"); }
        assertEquals("UNCERTAIN",jdbc.queryForObject("select status from ai_budget_reservations",String.class));
        assertEquals("CLAIMED",jdbc.queryForObject("select status from simulation_batches where batch_id=?",String.class,batch.id()));
        service.evaluate(batch); assertEquals(1,calls.get());
        assertEquals(1,jdbc.queryForObject("select count(*) from simulation_decisions where batch_id=?",Integer.class,batch.id()));
    }
    @Test void fabricatedPersonalReferenceInModelEvidenceNeverAuthorizesCriticalUrgency() {
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values (?,'CRITICAL_URGENCY','gemini',true,now()+interval '1 day')",chat);
        ProviderTransport fake = (request, timeout, max) -> gemini(valid().replace("NORMAL", "CRITICAL").replace("[]", "[\"owner explicitly mentioned me\"]"));
        Classification result = classifier(true, fake).classify(batch());
        assertFalse(result.available());
        assertFalse(result.explicitPersonalReference());
        assertFalse(result.groupTriggerMatched());
    }
    @Test void missingCredentialsOrUnapprovedPrivacyOrMonthlyLimitBlockBeforeHttp() {
        var missing = new AiClassificationProperties(true,null,"model",null,null,10,Duration.ofSeconds(1),null);
        assertFalse(new AiSimulationClassifier(jdbc,budgets,clock,missing,fake()).classify(batch()).available());
        jdbc.update("update ai_data_consents set privacy_approved=false");
        assertFalse(classifier(true,fake()).classify(batch()).available());
        jdbc.update("update ai_data_consents set privacy_approved=true");
        jdbc.update("update ai_budget_limits set monthly_limit_micros=null");
        assertFalse(classifier(true,fake()).classify(batch()).available());
        assertEquals(0,calls.get());
        assertEquals(0,jdbc.queryForObject("select count(*) from ai_budget_reservations",Integer.class));
    }
    @Test void cachedResultCannotOutliveRevokedPurposeOrFallbackConsent() {
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values (?,'CRITICAL_URGENCY','gemini',true,now()+interval '1 day')", chat);
        var ai = classifier(true, fake());
        var batch = batch(chat,mention());
        assertTrue(ai.classify(batch).available());
        jdbc.update("delete from ai_data_consents where situation='CRITICAL_URGENCY'");
        assertFalse(ai.classify(batch).available());
        assertEquals(1, calls.get());
    }
    @Test void concurrentBatchEvaluationDoesNotDuplicatePaidRequest() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        ProviderTransport fake = (request, timeout, max) -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new RuntimeException(error); }
            return gemini(valid());
        };
        var ai = classifier(true,fake); var batch = batch();
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> ai.classify(batch));
            try {
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> ai.classify(batch));
            } finally { release.countDown(); }
            assertTrue(first.get(5,java.util.concurrent.TimeUnit.SECONDS).available());
        }
        assertTrue(ai.classify(batch).available()); assertEquals(1,calls.get());
    }
    @Test void expiredCrashClaimAndTimeoutAreNeverAutomaticallyRedispatched() {
        var batch = batch();
        jdbc.update("insert into simulation_ai_evaluations(batch_id,lease_token,expires_at) values (?,?,now()-interval '1 second')",batch.id(),UUID.randomUUID());
        assertFalse(classifier(true,fake()).classify(batch).available()); assertEquals(0,calls.get());
        var next = batch();
        var ai = classifier(true,(request,timeout,max) -> { calls.incrementAndGet(); throw new RuntimeException("ambiguous timeout"); });
        assertFalse(ai.classify(next).available());
        assertFalse(ai.classify(next).available());
        assertEquals(2,calls.get());
        assertEquals(2,jdbc.queryForObject("select count(*) from ai_budget_reservations where status='UNCERTAIN'",Integer.class));
    }
    @Test void fallbackConsentRevocationInvalidatesCachedFallbackResult() {
        ProviderTransport fake = (request,timeout,max) -> {
            calls.incrementAndGet();
            return request.uri().getHost().contains("googleapis") ? new ProviderTransport.Response(503,"{}")
                    : new ProviderTransport.Response(200,"{\"choices\":[{\"message\":{\"content\":"+quote(valid())+"}}]}");
        };
        var ai = classifier(true,fake); var batch = batch();
        assertTrue(ai.classify(batch).available());
        jdbc.update("delete from ai_data_consents where provider='openrouter'");
        assertFalse(ai.classify(batch).available());
        assertFalse(ai.revalidate(batch).available()); assertEquals(2,calls.get());
    }
    @Test void privacyRouteFailureNeverBroadensRoutingOrRetries() {
        ProviderTransport fake = (request,timeout,max) -> {
            calls.incrementAndGet();
            return request.uri().getHost().contains("googleapis") ? new ProviderTransport.Response(503,"{}") : new ProviderTransport.Response(404,"{}");
        };
        assertFalse(classifier(true,fake).classify(batch()).available());
        assertEquals(2,calls.get());
        assertEquals(2,jdbc.queryForObject("select count(*) from ai_budget_reservations where status='UNCERTAIN'",Integer.class));
    }
    @Test void aggregateTelegramReportAndDisabledEvolutionStayIsolatedFromShadowDecision() {
        var ai = classifier(true, fake());
        var service = new SimulationService(jdbc, manager, clock, new WatcherSimulationProperties(), new DecisionEngine(),
                new PersistedPolicyRepository(jdbc, new PolicyResolver()), new AvailabilityService(new HolidayService(jdbc)), new WatcherProperties(), ai);
        var batch = batch(); service.evaluate(batch);
        AtomicInteger evolution = new AtomicInteger();
        var actions = new dev.hugo.watcher.notification.WhatsAppActionService(jdbc,manager,clock,false);
        actions.enqueue(batch.id());
        new dev.hugo.watcher.notification.WhatsAppActionDispatcher(jdbc,manager,actions,
                (jid,text,key) -> { evolution.incrementAndGet(); return dev.hugo.watcher.notification.EvolutionSendResult.unknown(); },clock,false).dispatchNext();
        assertEquals(0,evolution.get());
        var outbox = new dev.hugo.watcher.NotificationOutboxRepository(jdbc,manager);
        new dev.hugo.watcher.notification.ReportService(jdbc,manager,outbox,clock).schedule(LocalDate.now(clock));
        List<String> telegram = new ArrayList<>();
        var properties = new WatcherProperties(); properties.setTelegramBotToken("fake"); properties.setTelegramChatId(123L);
        new dev.hugo.watcher.NotificationService(jdbc,manager,outbox,
                text -> { telegram.add(text); return dev.hugo.watcher.TelegramDeliveryResult.success(); },
                new dev.hugo.watcher.TelegramMessageFormatter(),properties,clock,new dev.hugo.watcher.ArchiveModeService(jdbc,manager)).flush(clock.instant());
        assertEquals(1,telegram.size());
        assertFalse(telegram.getFirst().contains("Preciso falar"));
        assertFalse(telegram.getFirst().contains(chat));
        assertEquals(0,jdbc.queryForObject("select count(*) from outbox_actions where action_type='intelligent_alert'",Integer.class));
    }
    @Test void groupAndPrivateConsentCannotBeSubstitutedAndGroupsRequireObjectiveMention() {
        String group = "team@g.us";
        jdbc.update("delete from ai_data_consents");
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values (?,'GROUP_TRIGGER','gemini',true,now()+interval '1 day')",chat);
        assertFalse(classifier(true,fake()).classify(batch()).available());
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values (?,'CONTACT_REQUEST','gemini',true,now()+interval '1 day')",group);
        assertFalse(classifier(true,fake()).classify(batch(group,mention())).available());
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values (?,'GROUP_TRIGGER','gemini',true,now()+interval '1 day')",group);
        assertFalse(classifier(true,fake()).classify(batch(group,Map.of("conversation","@owner urgently reply"))).available());
        assertEquals(0,calls.get());
        var ai = classifier(true,(request,timeout,max) -> { calls.incrementAndGet(); return gemini(valid().replace("CONTACT_REQUEST","GROUP_TRIGGER")); });
        var result = ai.classify(batch(group,mention()));
        assertTrue(result.available()); assertTrue(result.groupTriggerMatched()); assertTrue(result.explicitPersonalReference()); assertEquals(1,calls.get());
    }
    @Test void realProducerPersistsModelMetadataAndQueuesBothChannelsFromOneAudit() {
        authorizeProducer();
        var batch = batch(chat, mention());
        var decision = producingService(true, critical()).evaluate(batch);
        assertTrue(decision.wouldReply()); assertTrue(decision.wouldAlert());
        assertEquals("CLASSIFIED", jdbc.queryForObject("select classification_status from simulation_decisions where batch_id=?", String.class, batch.id()));
        assertEquals("CRITICAL", jdbc.queryForObject("select classification_urgency from simulation_decisions where batch_id=?", String.class, batch.id()));
        assertEquals(0.9, jdbc.queryForObject("select classification_confidence from simulation_decisions where batch_id=?", Double.class, batch.id()));
        assertNotNull(jdbc.queryForObject("select intelligent_alert_reference_hash from simulation_decisions where batch_id=?", String.class, batch.id()));
        assertEquals(1, countActions()); assertEquals(1, countAlerts());
        String payload = jdbc.queryForObject("select payload::text from outbox_actions where action_type='intelligent_alert'", String.class);
        assertFalse(payload.contains(chat)); assertFalse(payload.contains("Preciso"));
    }
    @Test void shadowNeverEnqueuesEvenWithBothChannelFlagsEnabled() {
        authorizeProducer();
        var decision = producingService(false, critical()).evaluate(batch(chat, mention()));
        assertTrue(decision.wouldReply()); assertTrue(decision.wouldAlert());
        assertEquals(0, countActions()); assertEquals(0, countAlerts());
    }
    @Test void shadowAuditCannotBePromotedByRestartWithRealFlag() {
        authorizeProducer(); var batch=batch(chat,mention());
        producingService(false,critical()).evaluate(batch);
        producingService(true,critical()).evaluate(batch);
        assertEquals(0,countActions()); assertEquals(0,countAlerts()); assertEquals(1,calls.get());
    }
    @Test void concurrentProducerReplayAndRestartNeverDuplicateEitherChannel() throws Exception {
        authorizeProducer();
        var batch = batch(chat, mention());
        var service = producingService(true, critical());
        service.evaluate(batch);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.evaluate(batch));
            var second = pool.submit(() -> producingService(true, critical()).evaluate(batch));
            first.get(10,java.util.concurrent.TimeUnit.SECONDS); second.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, calls.get()); assertEquals(1, countActions()); assertEquals(1, countAlerts());
    }
    @Test void modelEvidenceCannotManufacturePersonalReferenceOrBypassRevocation() {
        authorizeProducer();
        producingService(true, critical()).evaluate(batch());
        assertEquals(0, countActions()); assertEquals(0, countAlerts());
        var next = batch(chat, mention());
        producingService(true, (request,timeout,max) -> {
            calls.incrementAndGet(); jdbc.update("delete from ai_data_consents"); return gemini(valid().replace("NORMAL","CRITICAL"));
        }).evaluate(next);
        assertEquals(0, countActions()); assertEquals(0, countAlerts());
    }
    @Test void providerKillSwitchPreventsProducerAndGroupWithoutStructuredTriggerNeverCallsModel() {
        authorizeProducer();
        producingService(true,(request,timeout,max) -> {
            calls.incrementAndGet(); jdbc.update("update automation_settings set kill_switch_enabled=true"); return gemini(valid());
        }).evaluate(batch(chat,mention()));
        assertEquals(0,countActions()); assertEquals(0,countAlerts());
        jdbc.update("update automation_settings set kill_switch_enabled=false");
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values ('team@g.us','GROUP_TRIGGER','gemini',true,now()+interval '1 day')");
        producingService(true, critical()).evaluate(batch("team@g.us",Map.of("conversation","@owner urgency")));
        assertEquals(1,calls.get()); assertEquals(0,countActions()); assertEquals(0,countAlerts());
    }
    @Test void producerFailureRollsBackDecisionAndRecoversWithoutAnotherProviderCall() {
        authorizeProducer(); var batch = batch(chat,mention()); var service = producingService(true,critical());
        jdbc.execute("create function reject_test_producer() returns trigger language plpgsql as $$ begin raise exception 'test producer failure'; end $$");
        jdbc.execute("create trigger reject_test_producer before insert on whatsapp_action_outbox for each row execute function reject_test_producer()");
        try { assertThrows(RuntimeException.class,() -> service.evaluate(batch)); }
        finally { jdbc.execute("drop trigger reject_test_producer on whatsapp_action_outbox"); jdbc.execute("drop function reject_test_producer()"); }
        assertEquals(0,jdbc.queryForObject("select count(*) from simulation_decisions",Integer.class));
        assertEquals(0,countAlerts()); assertEquals(0,countActions());
        service.evaluate(batch); assertEquals(1,calls.get()); assertEquals(1,countActions()); assertEquals(1,countAlerts());
    }
    @Test void pendingProducedWorkRevalidatesPrivacyScopeAndKillSwitchBeforeDelivery() {
        authorizeProducer(); var batch = batch(chat,mention()); producingService(true,critical()).evaluate(batch);
        var actions = new dev.hugo.watcher.notification.WhatsAppActionService(jdbc,manager,clock,true,"owner@s.whatsapp.net");
        var alerts = new dev.hugo.watcher.notification.IntelligentAlertService(jdbc,manager,new dev.hugo.watcher.NotificationOutboxRepository(jdbc,manager),clock,true,"owner@s.whatsapp.net");
        UUID id = jdbc.queryForObject("select action_id from whatsapp_action_outbox",UUID.class);
        var alert = alerts.enqueue(batch.id()).action();
        jdbc.update("update whatsapp_action_outbox set status='DISPATCHED'");
        jdbc.update("update outbox_actions set status='PROCESSING'");
        assertNotNull(actions.revalidate(id,clock.instant())); assertTrue(alerts.revalidate(alert,clock.instant()));
        jdbc.update("insert into response_templates(template_id,version,situation,body,identifies_automation,enabled) values ('critical_urgency',2,'CRITICAL_URGENCY','Resposta automática: versão nova.',true,true) on conflict do nothing");
        jdbc.update("update chat_automation_authorizations set template_version=2 where situation='CRITICAL_URGENCY'");
        assertFalse(alerts.revalidate(alert,clock.instant()),"new approval cannot replace audited alert template");
        jdbc.update("update chat_automation_authorizations set template_version=1 where situation='CRITICAL_URGENCY'");
        jdbc.update("update ai_data_consents set privacy_approved=false");
        assertNull(actions.revalidate(id,clock.instant())); assertFalse(alerts.revalidate(alert,clock.instant()));
        jdbc.update("update ai_data_consents set privacy_approved=true");
        jdbc.update("update archive_monitor_settings set mode='ARCHIVED_ONLY'");
        assertNull(actions.revalidate(id,clock.instant())); assertFalse(alerts.revalidate(alert,clock.instant()));
        jdbc.update("update archive_monitor_settings set mode='ALL'");
        jdbc.update("update automation_settings set kill_switch_enabled=true");
        assertNull(actions.revalidate(id,clock.instant())); assertFalse(alerts.revalidate(alert,clock.instant()));
    }
    @Test void personalReferenceUsesIndexedOwnerReplyWithoutAnyModelEvidence() {
        authorizeProducer();
        jdbc.update("insert into whatsapp_owner_message_references(chat_jid,external_id,observed_at,expires_at) values (?,'owner-outbound',now(),now()+interval '1 day')",chat);
        var content=Map.<String,Object>of("extendedTextMessage",Map.of("text","request","contextInfo",Map.of("stanzaId","owner-outbound")));
        producingService(true,critical()).evaluate(batch(chat,content));
        assertEquals(1,countActions()); assertEquals(1,countAlerts());
    }
    @Test void fabricatedInMemoryMentionCannotReplacePersistedContext() {
        authorizeProducer(); var actual=batch();
        var forged=new IncomingMessage(Optional.of(UUID.randomUUID().toString()),chat,Optional.empty(),Optional.empty(),Optional.empty(),false,"notify",false,mention(),clock.instant());
        producingService(true,critical()).evaluate(new Batch(actual.id(),chat,"",List.of(forged)));
        assertEquals(0,countActions()); assertEquals(0,countAlerts());
        assertNull(jdbc.queryForObject("select intelligent_alert_reference_hash from simulation_decisions where batch_id=?",String.class,actual.id()));
    }
    @Test void channelFlagsAndMissingConsentPreventProducerWork() {
        authorizeProducer();
        jdbc.update("update automation_settings set auto_reply_enabled=false,intelligent_alerts_enabled=false");
        producingService(true,critical()).evaluate(batch(chat,mention()));
        jdbc.update("update automation_settings set auto_reply_enabled=true,intelligent_alerts_enabled=true");
        jdbc.update("delete from ai_data_consents");
        producingService(true,critical()).evaluate(batch(chat,mention()));
        assertEquals(0,countActions()); assertEquals(0,countAlerts()); assertEquals(1,calls.get());
    }
    @Test void pendingHighAlertRechecksCurrentAvailabilityAndEvidenceExpiry() {
        authorizeProducer(); var batch=batch(chat,mention());
        producingService(true,(request,timeout,max)->gemini(valid().replace("NORMAL","HIGH"))).evaluate(batch);
        var alerts=new dev.hugo.watcher.notification.IntelligentAlertService(jdbc,manager,new dev.hugo.watcher.NotificationOutboxRepository(jdbc,manager),clock,true,"owner@s.whatsapp.net");
        var action=alerts.enqueue(batch.id()).action();
        jdbc.update("update outbox_actions set status='PROCESSING'");
        assertTrue(alerts.revalidate(action,clock.instant()));
        jdbc.update("insert into availability_overrides(singleton_id,state,starts_at,expires_at) values (1,'UNAVAILABLE',now()-interval '1 minute',now()+interval '1 hour')");
        assertFalse(alerts.revalidate(action,clock.instant()));
        jdbc.update("delete from availability_overrides");
        jdbc.update("update simulation_batches set expires_at=now()-interval '1 second' where batch_id=?",batch.id());
        assertFalse(alerts.revalidate(action,clock.instant()));
    }
    private void authorizeProducer() {
        jdbc.update("update automation_settings set auto_reply_enabled=true,intelligent_alerts_enabled=true,simulation_cooldown_seconds=0,real_cooldown_seconds=0");
        jdbc.update("update response_templates set body='Resposta automática: retorno em breve.',enabled=true,identifies_automation=true");
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CONTACT_REQUEST','contact_request',1),(?,'CRITICAL_URGENCY','critical_urgency',1)",chat,chat);
        jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values (?,'CRITICAL_URGENCY','gemini',true,now()+interval '1 day')",chat);
    }
    private ProviderTransport critical() { return (request,timeout,max) -> { calls.incrementAndGet(); return gemini(valid().replace("NORMAL","CRITICAL")); }; }
    private SimulationService producingService(boolean real, ProviderTransport transport) {
        var alerts = new dev.hugo.watcher.notification.IntelligentAlertService(jdbc,manager,new dev.hugo.watcher.NotificationOutboxRepository(jdbc,manager),clock,true,"owner@s.whatsapp.net");
        var actions = new dev.hugo.watcher.notification.WhatsAppActionService(jdbc,manager,clock,true,"owner@s.whatsapp.net");
        var producer = new dev.hugo.watcher.notification.DecisionActionProducer(jdbc, alerts, actions, real);
        return new SimulationService(jdbc, manager, clock, new WatcherSimulationProperties(), new DecisionEngine(),
                new PersistedPolicyRepository(jdbc,new PolicyResolver()),new AvailabilityService(new HolidayService(jdbc)),new WatcherProperties(),classifier(true,transport),producer);
    }
    private int countActions() { return jdbc.queryForObject("select count(*) from whatsapp_action_outbox",Integer.class); }
    private int countAlerts() { return jdbc.queryForObject("select count(*) from outbox_actions where action_type='intelligent_alert'",Integer.class); }
    private AiSimulationClassifier classifier(boolean enabled, ProviderTransport fake) {
        return new AiSimulationClassifier(jdbc, budgets, clock,
                new AiClassificationProperties(enabled,"fake-gemini","fake-model","fake-router","fake-model",10,Duration.ofSeconds(1),"owner@s.whatsapp.net"), fake);
    }
    private ProviderTransport fake() { return (request, timeout, max) -> {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); calls.incrementAndGet(); return gemini(valid());
    }; }
    private void consent(String provider) { jdbc.update("insert into ai_data_consents(chat_jid,situation,provider,privacy_approved,expires_at) values (?,'CONTACT_REQUEST',?,true,now()+interval '1 day')", chat, provider); }
    private Batch batch() {
        return batch(chat,Map.of("conversation","Preciso falar com você"));
    }
    private Map<String,Object> mention() {
        return Map.of("extendedTextMessage",Map.of("text","Preciso falar com você","contextInfo",Map.of("mentionedJid",List.of("owner@s.whatsapp.net"))));
    }
    private Batch batch(String jid,Map<String,Object> content) {
        Instant now = clock.instant();
        var debounce = new DebounceService(jdbc, new WatcherSimulationProperties());
        debounce.enqueue(new IncomingMessage(Optional.of(UUID.randomUUID().toString()),jid,Optional.empty(),Optional.empty(),Optional.empty(),false,"notify",false,content,now), now);
        return debounce.claimReady(now.plusSeconds(60)).getFirst();
    }
    private static String valid() { return "{\"intent\":\"CONTACT_REQUEST\",\"requestDetected\":true,\"urgency\":\"NORMAL\",\"evidence\":[],\"confidence\":0.9}"; }
    private static String quote(String value) { return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value); }
    private static ProviderTransport.Response gemini(String value) { return new ProviderTransport.Response(200,"{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"+quote(value)+"}]}}]}"); }
}
