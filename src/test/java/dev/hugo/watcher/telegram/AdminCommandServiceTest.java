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
        jdbc.update("delete from telegram_admin_audit");
        jdbc.update("delete from telegram_admin_callback_tokens");
        jdbc.update("delete from outbox_actions where action_type='telegram_admin_reply'");
        tokens = new CallbackTokenService(jdbc, clock);
        var outbox = new NotificationOutboxRepository(jdbc, new DataSourceTransactionManager(source));
        commands = new AdminCommandService(jdbc, new DataSourceTransactionManager(source), tokens, outbox, clock);
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
        assertTrue(jdbc.queryForObject("select kill_switch_enabled from automation_settings where singleton_id=1", Boolean.class));
        assertFalse(jdbc.queryForObject("select intelligent_alerts_enabled from automation_settings where singleton_id=1", Boolean.class));
        assertFalse(jdbc.queryForObject("select auto_reply_enabled from automation_settings where singleton_id=1", Boolean.class));
        assertFalse(jdbc.queryForObject("select ai_classification_enabled from automation_settings where singleton_id=1", Boolean.class));
        assertEquals("emergencia", jdbc.queryForObject("select action from telegram_admin_audit order by id desc limit 1", String.class));
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
        assertTrue(jdbc.queryForObject("select simulation_enabled from telegram_admin_settings where singleton_id=1", Boolean.class));

        AdminResult changed = commands.execute(command("simulacao", List.of("off"), true));

        assertTrue(changed.accepted());
        assertFalse(jdbc.queryForObject("select simulation_enabled from telegram_admin_settings where singleton_id=1", Boolean.class));
        String payload = jdbc.queryForObject("select payload::text from outbox_actions where action_type='telegram_admin_reply' order by id desc limit 1", String.class);
        OutboxAction action = new OutboxAction(1, "fixture", "telegram_admin_reply", payload, "[]", 0,
                clock.instant(), clock.instant().plusSeconds(60), null);
        assertTrue(new TelegramMessageFormatter().format(action).contains("Simulação desligado."));
        assertEquals(1, jdbc.queryForObject("select count(*) from telegram_admin_audit where action='simulacao'", Integer.class));
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
}
