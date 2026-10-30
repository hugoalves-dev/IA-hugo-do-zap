package dev.hugo.watcher.telegram;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.OutboxAction;
import dev.hugo.watcher.TelegramMessageFormatter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class AdminCommandServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private AdminCommandService commands;
    private CallbackTokenService tokens;
    private final MutableClock clock = new MutableClock();

    @BeforeEach void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("update operational_restrictions set emergency_latched=false,simulation_paused=false");
        jdbc.update("delete from telegram_admin_audit");
        jdbc.update("delete from telegram_admin_callback_tokens");
        jdbc.update("delete from outbox_actions where action_type='telegram_admin_reply'");
        tokens = new CallbackTokenService(jdbc, clock);
        var outbox = new NotificationOutboxRepository(jdbc, new DataSourceTransactionManager(source));
        commands = new AdminCommandService(jdbc, new DataSourceTransactionManager(source), tokens, outbox, clock);
        commands.configureOperationalState(dev.hugo.watcher.TestOperational.state(jdbc));
    }

    @Test void menuUsesOpaqueCallbackTokensAndCancelRevokesConfirmation() {
        var admin = new TelegramAdminProperties();
        admin.setWebhookEnabled(true);
        admin.setUserId(1001L);
        admin.setWebhookSecret("abcdefghijklmnopqrstuvwxyz-01234567890");
        commands.configureTelegramMenu(admin);

        assertTrue(commands.execute(command("menu", List.of(), false)).accepted());
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        String menu = jdbc.queryForObject("""
                select payload::text from outbox_actions where action_type='telegram_admin_reply'
                order by id desc limit 1
                """, String.class);
        var root = json.readTree(menu);
        assertTrue(root.path("keyboard").isArray());
        String token = root.path("keyboard").get(0).get(0).path("data").asString();
        assertTrue(token.length() <= 64);
        assertFalse(menu.contains("1001"));
        assertFalse(menu.contains("2002"));
        CallbackAction navigation = tokens.consume(token, 1001, 2002);
        assertEquals("nav_status", navigation.command());

        var pending = commands.execute(new AdminCommand("nav_ocupado", List.of("60"), 1001, 2002, false, 42));
        assertTrue(pending.confirmationRequired());
        assertEquals(0, jdbc.queryForObject("select count(*) from availability_overrides", Integer.class));
        String prompt = jdbc.queryForObject("""
                select payload::text from outbox_actions where action_type='telegram_admin_edit'
                order by id desc limit 1
                """, String.class);
        var keyboard = json.readTree(prompt).path("keyboard").get(0);
        String confirm = keyboard.get(0).path("data").asString();
        String cancel = keyboard.get(1).path("data").asString();
        assertEquals(42, json.readTree(prompt).path("messageId").asInt());
        assertEquals("cancel", tokens.consume(cancel, 1001, 2002).command());
        CallbackAction cancellation = new CallbackAction("cancel", List.of(confirm));
        assertTrue(commands.execute(new AdminCommand(cancellation.command(),
                cancellation.arguments(), 1001, 2002, true, 42)).accepted());
        assertThrows(CallbackTokenRejectedException.class, () -> tokens.consume(confirm, 1001, 2002));
        assertEquals(0, jdbc.queryForObject("select count(*) from availability_overrides", Integer.class));
    }

    @Test void availabilityOverrideRequiresConfirmationAndExpires() {
        AdminResult pending = commands.execute(command("ocupado", List.of("30"), false));
        assertTrue(pending.confirmationRequired());
        assertEquals(0, jdbc.queryForObject("select count(*) from availability_overrides", Integer.class));

        AdminResult changed = commands.execute(command("ocupado", List.of("30"), true));

        assertTrue(changed.accepted());
        assertEquals("UNAVAILABLE", jdbc.queryForObject("select state from availability_overrides where singleton_id=1", String.class));
        assertEquals(clock.instant().plusSeconds(1800), jdbc.queryForObject("select expires_at from availability_overrides where singleton_id=1", java.sql.Timestamp.class).toInstant());
        assertEquals(1, jdbc.queryForObject("select count(*) from telegram_admin_audit where action='ocupado'", Integer.class));
    }

    @Test void emergencyStopsRealActionsWithoutClassificationServiceOrSecretAudit() {
        AdminResult result = commands.execute(command("emergencia", List.of(), false));

        assertTrue(result.accepted());
        assertTrue(jdbc.queryForObject("select emergency_latched from operational_restrictions where singleton_id=1", Boolean.class));
        assertTrue(commands.execute(command("recuperar", List.of(), false)).confirmationRequired());
        assertTrue(jdbc.queryForObject("select emergency_latched from operational_restrictions where singleton_id=1", Boolean.class));
        assertTrue(commands.execute(command("recuperar", List.of(), true)).accepted());
        assertFalse(jdbc.queryForObject("select emergency_latched from operational_restrictions where singleton_id=1", Boolean.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from telegram_admin_audit where action='emergencia'", Integer.class));
    }

    @Test void statusReportsEffectiveEmergencyAndRecoveryRequiresConfirmation() {
        assertTrue(commands.execute(command("status", List.of(), false)).message().contains("IA ligado"));
        commands.execute(command("emergencia", List.of(), false));
        String status=commands.execute(command("status", List.of(), false)).message();
        assertTrue(status.contains("emergência ligado")); assertTrue(status.contains("IA desligado"));
        commands.configureOperationalState(dev.hugo.watcher.TestOperational.state(jdbc));
        assertTrue(commands.execute(command("status", List.of(), false)).message().contains("emergência ligado"));
        assertTrue(commands.execute(command("recuperar", List.of(), false)).confirmationRequired());
        assertTrue(commands.execute(command("status", List.of(), false)).message().contains("emergência ligado"));
    }

    @Test void statusShowsInvalidAiConfigurationWithoutSecrets() {
        var flags = new dev.hugo.watcher.AutomationProperties();
        flags.setKillSwitchEnabled(false);
        var ai = new dev.hugo.watcher.ai.AiClassificationProperties(true, null, null, null, "model", 1,
                java.time.Duration.ofSeconds(1), null, true, false, true, false);
        commands.configureOperationalState(new dev.hugo.watcher.OperationalState(jdbc, flags, ai,
                new dev.hugo.watcher.simulation.WatcherSimulationProperties(), true));
        String status = commands.execute(command("status", List.of(), false)).message();
        assertTrue(status.contains("configuration_invalid"));
        assertFalse(status.contains("IA ligado"));
    }

    @Test void statusDoesNotClaimRealActionsAreEnabledWhenRealModeIsOff() {
        var flags = new dev.hugo.watcher.AutomationProperties();
        flags.setKillSwitchEnabled(false); flags.setAutoReplyEnabled(true); flags.setIntelligentAlertsEnabled(true);
        var ai = new dev.hugo.watcher.ai.AiClassificationProperties(true, null, null, "fake", "model", 1,
                java.time.Duration.ofSeconds(1), null, true, false, true, false);
        commands.configureOperationalState(new dev.hugo.watcher.OperationalState(jdbc, flags, ai,
                new dev.hugo.watcher.simulation.WatcherSimulationProperties(), false));
        String status = commands.execute(command("status", List.of(), false)).message();
        assertTrue(status.contains("alertas reais desligado"));
        assertTrue(status.contains("respostas reais desligado"));
    }

    @Test void menusExposePolicyTemplateHolidayAndBudgetControlsWithoutSecrets() {
        assertTrue(commands.execute(command("perfis", List.of(), false)).message().contains("/perfil"));
        assertTrue(commands.execute(command("templates", List.of(), false)).message().contains("/template"));
        assertTrue(commands.execute(command("feriados", List.of(), false)).message().contains("/feriado"));
        assertTrue(commands.execute(command("orcamento", List.of(), false)).message().contains("/orcamento"));
        assertFalse(commands.execute(command("status", List.of(), false)).message().contains("synthetic-test-token"));
    }

    @Test void simulationToggleIsConfirmedAndReplyUsesTheDurableOutbox() {
        AdminResult pending = commands.execute(command("simulacao", List.of("off"), false));
        assertTrue(pending.confirmationRequired());
        assertFalse(jdbc.queryForObject("select simulation_paused from operational_restrictions where singleton_id=1", Boolean.class));

        AdminResult changed = commands.execute(command("simulacao", List.of("off"), true));

        assertTrue(changed.accepted());
        assertTrue(jdbc.queryForObject("select simulation_paused from operational_restrictions where singleton_id=1", Boolean.class));
        String payload = jdbc.queryForObject("select payload::text from outbox_actions where action_type='telegram_admin_reply' order by id desc limit 1", String.class);
        OutboxAction action = new OutboxAction(1, "fixture", "telegram_admin_reply", payload, "[]", 0,
                clock.instant(), clock.instant().plusSeconds(60), null);
        assertTrue(new TelegramMessageFormatter().format(action).contains("Simulação pausada."));
        assertEquals(1, jdbc.queryForObject("select count(*) from telegram_admin_audit where action='simulacao'", Integer.class));
    }

    @Test void recentDecisionsAndDetailRespectRetentionAndDoNotRevealMessages() {
        var id = java.util.UUID.randomUUID();
        var now = java.sql.Timestamp.from(clock.instant());
        var expires = java.sql.Timestamp.from(clock.instant().plusSeconds(3600));
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,'','COMPLETED',?,?,?,?,?,?)", id, "fixture@s.whatsapp.net", now, now, now, now, expires, expires);
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at,classification_status,classification_urgency,classification_confidence) values (?,false,false,'no_request_detected',?,?,'CLASSIFIED','LOW',0.95)", id, now, expires);
        String recent = commands.execute(command("ultimas", List.of("5"), false)).message();
        assertTrue(recent.contains(id.toString().substring(0, 8)));
        assertTrue(recent.contains("LOW"));
        assertTrue(recent.contains("Contato indisponível"));
        assertTrue(recent.contains("27/10/2026 - 09:00:00"));
        String detail = commands.execute(command("decisao", List.of(id.toString().substring(0, 8)), false)).message();
        assertTrue(detail.contains("would_reply: false"));
        assertTrue(detail.contains("motivo específico não armazenado"));
        assertTrue(detail.contains("Prévia indisponível"));
        assertFalse(detail.contains("fixture@s.whatsapp.net"));
        assertTrue(commands.execute(command("decisao", List.of("invalid"), false)).message().contains("ID inválido"));
        clock.now = clock.now.plusSeconds(3601);
        assertTrue(commands.execute(command("decisao", List.of(id.toString().substring(0, 8)), false)).message().contains("expirada"));
    }

    @Test void recentDecisionsShowNotifyNameWithoutExposingIdentity() {
        var id = java.util.UUID.randomUUID();
        var now = java.sql.Timestamp.from(clock.instant());
        var expires = java.sql.Timestamp.from(clock.instant().plusSeconds(3600));
        String chat = "fixture-notify@s.whatsapp.net";
        jdbc.update("insert into contact_identity(canonical_jid,display_name,display_name_source) values (?,?,'NOTIFY')", chat, "Amazon & Filhos");
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,'','COMPLETED',?,?,?,?,?,?)", id, chat, now, now, now, now, expires, expires);
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at) values (?,false,false,'no_request_detected',?,?)", id, now, expires);
        String result = commands.execute(command("ultimas", List.of("10"), false)).message();
        assertTrue(result.contains("Amazon &amp; Filhos"));
        assertFalse(result.contains(chat));
    }

    @Test void diagnosticIsShownForNewDecisionsWithoutInventingOldReasons() {
        var id = java.util.UUID.randomUUID();
        var now = java.sql.Timestamp.from(clock.instant());
        var expires = java.sql.Timestamp.from(clock.instant().plusSeconds(3600));
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,'','COMPLETED',?,?,?,?,?,?)", id, "diag@s.whatsapp.net", now, now, now, now, expires, expires);
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,blocked_reason,classification_status,classification_diagnostic,decided_at,metadata_expires_at) values (?,false,false,'classification_unavailable','classification_unavailable','UNAVAILABLE','budget_exceeded',?,?)", id, now, expires);
        assertTrue(commands.execute(command("ultimas", List.of("10"), false)).message().contains("budget_exceeded"));
        assertTrue(commands.execute(command("decisao", List.of(id.toString().substring(0, 8)), false)).message().contains("budget_exceeded"));
    }

    @Test void truncatedDiagnosticPersistsAndRendersViaTelegramWithoutSensitiveData() {
        var id = java.util.UUID.randomUUID();
        var now = java.sql.Timestamp.from(clock.instant());
        var expires = java.sql.Timestamp.from(clock.instant().plusSeconds(3600));
        String chat = "secret-synthetic@s.whatsapp.net";
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,'','COMPLETED',?,?,?,?,?,?)",
                id, chat, now, now, now, now, expires, expires);
        jdbc.update("""
                insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,blocked_reason,
                    classification_status,classification_urgency,classification_confidence,classification_diagnostic,
                    decided_at,metadata_expires_at)
                values (?,false,false,'classification_unavailable','classification_unavailable',
                    'UNAVAILABLE','UNCERTAIN',0,'provider_output_truncated',?,?)
                """, id, now, expires);

        assertEquals("provider_output_truncated", jdbc.queryForObject(
                "select classification_diagnostic from simulation_decisions where batch_id=?", String.class, id));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from simulation_decisions where batch_id=? and (would_alert or would_reply)", Integer.class, id));

        String recent = commands.execute(command("ultimas", List.of("10"), false)).message();
        assertTrue(recent.contains("provider_output_truncated"));
        assertTrue(recent.contains("UNAVAILABLE / UNCERTAIN / 0.00"));
        String payload = jdbc.queryForObject(
                "select payload::text from outbox_actions where action_type='telegram_admin_reply' order by id desc limit 1", String.class);
        var action = new OutboxAction(1, "synthetic", "telegram_admin_reply", payload, "[]", 0,
                clock.instant(), clock.instant().plusSeconds(60), null);
        String telegram = new TelegramMessageFormatter().format(action);
        assertTrue(telegram.contains("provider_output_truncated"));
        assertFalse(telegram.contains(chat));
        assertFalse(telegram.contains("synthetic private prompt"));

        String detail = commands.execute(command("decisao", List.of(id.toString().substring(0, 8)), false)).message();
        assertTrue(detail.contains("diagnóstico=provider_output_truncated"));
        assertFalse(detail.contains(chat));
    }

    @Test void recentDecisionUsesSafeGroupSnapshotName() {
        var id = java.util.UUID.randomUUID();
        var now = java.sql.Timestamp.from(clock.instant());
        var expires = java.sql.Timestamp.from(clock.instant().plusSeconds(3600));
        String group = "fixture-group@g.us";
        jdbc.update("insert into chat_snapshot(chat_jid,display_name,is_group,synchronized_snapshot) values (?,? ,true,true) on conflict(chat_jid) do update set display_name=excluded.display_name", group, "Equipe <JC>");
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,'member','COMPLETED',?,?,?,?,?,?)", id, group, now, now, now, now, expires, expires);
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at) values (?,false,false,'no_request_detected',?,?)", id, now, expires);
        String result = commands.execute(command("ultimas", List.of("10"), false)).message();
        assertTrue(result.contains("Equipe &lt;JC&gt;"));
        assertFalse(result.contains(group));
    }

    @Test void hypotheticalPreviewRequiresCurrentOptInAndEscapesHtml() {
        var id = java.util.UUID.randomUUID();
        var now = java.sql.Timestamp.from(clock.instant());
        var expires = java.sql.Timestamp.from(clock.instant().plusSeconds(3600));
        String chat = "preview@s.whatsapp.net";
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,'','COMPLETED',?,?,?,?,?,?)", id, chat, now, now, now, now, expires, expires);
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at,classification_status,classification_urgency,classification_confidence,availability_state,availability_source) values (?,false,false,'no_authorized_action',?,?,'CLASSIFIED','LOW',0.95,'UNAVAILABLE','SCHEDULE')", id, now, expires);
        jdbc.update("insert into simulation_ai_evaluations(batch_id,lease_token,expires_at,result) values (?,?,?,?::jsonb)", id, java.util.UUID.randomUUID(), expires, "{\"provider\":\"openrouter\",\"requestDetected\":true,\"situations\":[\"CONTACT_REQUEST\"],\"contextCount\":2,\"contextWindowHours\":24}");
        jdbc.update("insert into response_templates(template_id,version,situation,body,identifies_automation,enabled) values ('fixture',1,'CONTACT_REQUEST','Resposta automática: & <teste>',true,true)");
        jdbc.update("insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version) values (?,'CONTACT_REQUEST','fixture',1)", chat);
        String detail = commands.execute(command("decisao", List.of(id.toString().substring(0,8)), false)).message();
        assertTrue(detail.contains("Prévia hipotética se habilitado"));
        assertTrue(detail.contains("&amp; &lt;teste&gt;"));
        assertTrue(detail.contains("would_reply: false"));
        assertTrue(detail.contains("contexto: 2 mensagens"));
        jdbc.update("delete from chat_automation_authorizations where chat_jid=?", chat);
        assertTrue(commands.execute(command("decisao", List.of(id.toString().substring(0,8)), false)).message().contains("Prévia indisponível"));
    }

    @Test void recentVolumeBoundsAndEscapesUntrustedContactNames() {
        var now = java.sql.Timestamp.from(clock.instant());
        var expires = java.sql.Timestamp.from(clock.instant().plusSeconds(3600));
        for (int i=0; i<10; i++) {
            var id=java.util.UUID.randomUUID();
            String chat="volume"+i+"@s.whatsapp.net";
            jdbc.update("insert into contact_identity(canonical_jid,display_name,display_name_source) values (?,?, 'CONTACT_NAME')", chat, "<".repeat(100));
            jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,'','COMPLETED',?,?,?,?,?,?)", id,chat,now,now,now,now,expires,expires);
            jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,blocked_reason,decided_at,metadata_expires_at) values (?,false,false,'blocked',?,?,?)",id,"a".repeat(120),now,expires);
        }
        String text=commands.execute(command("ultimas",List.of("10"),false)).message();
        assertTrue(text.length() < 3500);
        assertTrue(text.contains("&lt;"));
        assertFalse(text.contains("<"));
        assertTrue(commands.execute(command("ultimas",List.of("11"),false)).message().contains("1-10"));
    }

    private AdminCommand command(String name, List<String> args, boolean confirmed) {
        return new AdminCommand(name, args, 1001, 2002, confirmed);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T12:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    @Test void reportCommandUsesFreshDataOnEveryPrivateRequest() {
        var source = jdbc.getDataSource();
        var manager = new DataSourceTransactionManager(source);
        var outbox = new NotificationOutboxRepository(jdbc, manager);
        var reportService = new dev.hugo.watcher.notification.ReportService(jdbc, manager, outbox, clock);
        commands = new AdminCommandService(jdbc, manager, tokens, outbox, clock, reportService);

        assertTrue(commands.execute(command("relatorio", List.of(), false)).accepted());
        String before = jdbc.queryForObject("""
                select payload::text from outbox_actions
                 where action_type='simulation_daily_summary' order by id desc limit 1
                """, String.class);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        int baseline = json.readTree(before).path("decisionCount").asInt();

        var batch = java.util.UUID.randomUUID();
        var timestamp = java.sql.Timestamp.from(clock.instant());
        var expires = java.sql.Timestamp.from(clock.instant().plusSeconds(3600));
        jdbc.update("insert into simulation_batches(batch_id,chat_jid,participant_jid,status,first_message_at,last_message_at,due_at,created_at,expires_at,metadata_expires_at) values (?,?,'','COMPLETED',?,?,?,?,?,?)",
                batch, "report-test@s.whatsapp.net", timestamp, timestamp, timestamp, timestamp, expires, expires);
        jdbc.update("insert into simulation_decisions(batch_id,would_alert,would_reply,decision_reason,decided_at,metadata_expires_at) values (?,false,false,'no_request_detected',?,?)",
                batch, timestamp, expires);

        assertTrue(commands.execute(command("relatorio", List.of(), false)).accepted());
        String after = jdbc.queryForObject("""
                select payload::text from outbox_actions
                 where action_type='simulation_daily_summary' order by id desc limit 1
                """, String.class);
        assertEquals(baseline + 1, json.readTree(after).path("decisionCount").asInt());
        assertFalse(after.contains("report-test@s.whatsapp.net"));
    }

}
