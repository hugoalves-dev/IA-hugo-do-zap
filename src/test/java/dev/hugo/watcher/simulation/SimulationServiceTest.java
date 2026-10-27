package dev.hugo.watcher.simulation;

import dev.hugo.watcher.ArchiveMode;
import dev.hugo.watcher.IncomingMessage;
import dev.hugo.watcher.RetentionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class SimulationServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private DebounceService debounce;
    private SimulationService simulations;

    @BeforeEach void migrateAndReset() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).load().migrate();
        jdbc.update("delete from simulation_decisions");
        jdbc.update("delete from chat_automation_authorizations");
        jdbc.update("delete from decision_cooldowns");
        jdbc.update("delete from simulation_batch_messages");
        jdbc.update("delete from simulation_batches");
        jdbc.update("update archive_monitor_settings set mode='ALL' where singleton_id=1");
        jdbc.update("update automation_settings set ai_classification_enabled=false,intelligent_alerts_enabled=false,auto_reply_enabled=false,kill_switch_enabled=true where singleton_id=1");
        debounce = new DebounceService(jdbc, new WatcherSimulationProperties());
        simulations = new SimulationService(jdbc);
    }

    @Test void unknownArchiveStateIsEligibleOnlyInAllAndDecisionIsAudited() {
        Batch batch = enqueueAndClaim("unknown@s.whatsapp.net");
        SimulationDecision eligible = simulations.evaluate(batch);
        assertFalse(eligible.wouldAlert());
        assertFalse(eligible.wouldReply());
        assertEquals("ai_classification_disabled", eligible.decisionReason());
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_decisions where batch_id=?", Integer.class, batch.id()));
        assertEquals(false, jdbc.queryForObject("select would_alert from simulation_decisions where batch_id=?", Boolean.class, batch.id()));
        assertEquals(false, jdbc.queryForObject("select would_reply from simulation_decisions where batch_id=?", Boolean.class, batch.id()));
        assertNull(jdbc.queryForObject("select template_id from simulation_decisions where batch_id=?", String.class, batch.id()));

        jdbc.update("update archive_monitor_settings set mode='ARCHIVED_ONLY' where singleton_id=1");
        Batch filtered = enqueueAndClaim("unknown-filtered@s.whatsapp.net");
        SimulationDecision excluded = simulations.evaluate(filtered);
        assertEquals("archive_state_unknown", excluded.blockedReason());
        jdbc.update("update archive_monitor_settings set mode='UNARCHIVED_ONLY' where singleton_id=1");
        assertEquals("archive_state_unknown", simulations.evaluate(enqueueAndClaim("unknown-unarchived@s.whatsapp.net")).blockedReason());
    }

    @Test void blockedScopePersistsAndPersistenceFailureDoesNotCompleteBatch() {
        jdbc.update("insert into chat_snapshot(chat_jid, archived, synchronized_snapshot) values (?, true, true)", "archived@s.whatsapp.net");
        jdbc.update("update archive_monitor_settings set mode='UNARCHIVED_ONLY' where singleton_id=1");
        Batch blocked = enqueueAndClaim("archived@s.whatsapp.net");
        assertEquals("archive_scope_excluded", simulations.evaluate(blocked).blockedReason());

        Batch failing = enqueueAndClaim("persist-fail@s.whatsapp.net");
        UUID id = failing.id();
        jdbc.execute("drop table simulation_decisions");
        assertThrows(RuntimeException.class, () -> simulations.evaluate(failing));
        assertEquals("CLAIMED", jdbc.queryForObject("select status from simulation_batches where batch_id=?", String.class, id));
    }

    @Test void sensitiveBatchExpiresBeforeItsLongerLivedAudit() {
        Batch batch = enqueueAndClaim("retention@s.whatsapp.net");
        simulations.evaluate(batch);
        Instant cutoff = Instant.parse("2026-10-27T10:00:01Z");
        jdbc.update("update simulation_batches set expires_at=?, metadata_expires_at=? where batch_id=?",
                java.sql.Timestamp.from(cutoff.minusSeconds(1)), java.sql.Timestamp.from(cutoff.minusSeconds(1)), batch.id());
        jdbc.update("update simulation_decisions set metadata_expires_at=? where batch_id=?",
                java.sql.Timestamp.from(cutoff.plusSeconds(86_400)), batch.id());
        var retention = new RetentionService(jdbc, new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));

        retention.purge(cutoff);
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_decisions where batch_id=?", Integer.class, batch.id()));
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_batches where batch_id=?", Integer.class, batch.id()));

        jdbc.update("update simulation_decisions set metadata_expires_at=? where batch_id=?",
                java.sql.Timestamp.from(cutoff.minusSeconds(1)), batch.id());
        retention.purge(cutoff);
        assertEquals(0, jdbc.queryForObject("select count(*) from simulation_decisions where batch_id=?", Integer.class, batch.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from simulation_batches where batch_id=?", Integer.class, batch.id()));
    }

    @Test void repeatedEvaluationReturnsThePersistedDecisionAfterScopeChanges() {
        Batch batch = enqueueAndClaim("idempotent@s.whatsapp.net");
        SimulationDecision first = simulations.evaluate(batch);
        jdbc.update("update archive_monitor_settings set mode='ARCHIVED_ONLY' where singleton_id=1");

        SimulationDecision repeated = simulations.evaluate(batch);

        assertEquals(first, repeated);
        assertEquals("ai_classification_disabled", repeated.blockedReason());
        assertEquals("ai_classification_disabled", repeated.decisionReason());
        assertEquals(1, jdbc.queryForObject("select count(*) from simulation_decisions where batch_id=?", Integer.class, batch.id()));
    }

    @Test void engineDecisionAndReasonArePersistedWithoutTreatingRawMessageAsIntent() {
        String chat = "classified@s.whatsapp.net";
        jdbc.update("update automation_settings set ai_classification_enabled=true, auto_reply_enabled=true where singleton_id=1");
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,?,?,?)",
                chat, "CONTACT_REQUEST", "contact_request", 1);
        Batch batch = enqueueAndClaim(chat);

        SimulationDecision decision = simulations.evaluate(batch);

        assertFalse(decision.wouldReply());
        assertEquals("classification_unavailable", decision.decisionReason());
        assertFalse(decision.realActionAllowed());
        assertEquals("classification_unavailable", decision.realBlockedReason());
        assertEquals("classification_unavailable", jdbc.queryForObject(
                "select decision_reason from simulation_decisions where batch_id=?", String.class, batch.id()));
        assertEquals("BLOCKED", jdbc.queryForObject(
                "select decision_state from simulation_decisions where batch_id=?", String.class, batch.id()));
    }

    @Test void authorizationCannotReferenceTemplateForDifferentSituation() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,?,?,?)",
                "mismatched-template@g.us", "GROUP_TRIGGER", "contact_request", 1));
    }

    @Test void preapprovedAutomationTemplateRequiresDisclosurePrefixInContent() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into response_templates(template_id,version,situation,body,identifies_automation,enabled)
                values ('undisclosed',1,'CONTACT_REQUEST','Oi',true,true)
                """));
    }

    private Batch enqueueAndClaim(String chat) {
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        IncomingMessage message = new IncomingMessage(Optional.of(UUID.randomUUID().toString()), chat, Optional.empty(),
                Optional.empty(), Optional.empty(), false, "notify", false, Map.of("conversation", "hello"), now);
        debounce.enqueue(message, now);
        return debounce.claimReady(now.plusSeconds(10)).getFirst();
    }
}
