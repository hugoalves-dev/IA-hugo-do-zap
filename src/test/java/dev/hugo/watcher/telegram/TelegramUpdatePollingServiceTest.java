package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.User;
import dev.hugo.watcher.NotificationOutboxRepository;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doAnswer;

@Testcontainers(disabledWithoutDocker = true)
class TelegramUpdatePollingServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private org.springframework.jdbc.datasource.DriverManagerDataSource source;

    @BeforeEach void migrate() {
        source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("update telegram_admin_settings set polling_offset=0 where singleton_id=1");
        jdbc.update("delete from telegram_admin_audit");
        jdbc.update("delete from telegram_admin_callback_tokens");
        jdbc.update("delete from outbox_actions where action_type='telegram_admin_reply'");
    }

    @Test void advancesDurableOffsetAfterHandlingUpdateWithoutTelegramNetwork() {
        Update update = mock(Update.class);
        when(update.updateId()).thenReturn(41);
        AtomicInteger calls = new AtomicInteger();
        TelegramUpdatesClient client = offset -> { assertEquals(0, offset); calls.incrementAndGet(); return List.of(update); };
        TelegramUpdateHandler handler = mock(TelegramUpdateHandler.class);

        new TelegramUpdatePollingService(jdbc, client, handler).poll();

        assertEquals(42L, jdbc.queryForObject("select polling_offset from telegram_admin_settings where singleton_id=1", Long.class));
        assertEquals(1, calls.get());
        verify(handler).handle(update);
    }

    @Test void databaseAdvisoryLockAllowsOnlyOnePollingConsumer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TelegramUpdatesClient client = offset -> { calls.incrementAndGet(); return List.of(); };
        try (Connection owner = source.getConnection()) {
            owner.createStatement().execute("select pg_advisory_lock(73491,22)");
            new TelegramUpdatePollingService(jdbc, client, mock(TelegramUpdateHandler.class)).poll();
            assertEquals(0, calls.get());
            owner.createStatement().execute("select pg_advisory_unlock(73491,22)");
        }
    }

    @Test void rollsBackAdminMutationWhenOffsetCommitCannotComplete() {
        Update update = mock(Update.class);
        when(update.updateId()).thenReturn(75);
        TelegramUpdatesClient client = offset -> List.of(update);
        TelegramUpdateHandler handler = mock(TelegramUpdateHandler.class);
        doAnswer(invocation -> {
            jdbc.update("insert into telegram_admin_audit(action,outcome,actor_user_id,actor_chat_id,created_at) values ('simulacao','APPLIED',1001,2002,now())");
            throw new IllegalStateException("simulate failure before offset commit");
        }).when(handler).handle(update);

        new TelegramUpdatePollingService(jdbc, client, handler).poll();

        assertEquals(0, jdbc.queryForObject("select count(*) from telegram_admin_audit", Integer.class));
        assertEquals(0L, jdbc.queryForObject("select polling_offset from telegram_admin_settings where singleton_id=1", Long.class));
    }

    @Test void invalidConfirmedBudgetCommandIsAuditedAndDoesNotBlockLaterPollingUpdates() {
        Clock clock = Clock.systemUTC();
        var manager = new DataSourceTransactionManager(source);
        var callbacks = new CallbackTokenService(jdbc, clock);
        var outbox = new NotificationOutboxRepository(jdbc, manager);
        var commands = new AdminCommandService(jdbc, manager, callbacks, outbox, clock);
        var handler = new TelegramUpdateHandler(1001, 2002, commands, callbacks);
        TelegramUpdatesClient client = offset -> {
            if (offset == 0) return List.of(messageUpdate(0, "/orcamento 10"));
            if (offset == 1) {
                String payload = jdbc.queryForObject("select payload::text from outbox_actions where action_type='telegram_admin_reply' order by id desc limit 1", String.class);
                Matcher token = Pattern.compile("/confirm ([A-Za-z0-9_-]+)").matcher(payload);
                if (!token.find()) throw new AssertionError("confirmation token missing from admin reply");
                return List.of(messageUpdate(1, "/confirm " + token.group(1)));
            }
            if (offset == 2) return List.of(messageUpdate(2, "/status"));
            return List.of();
        };
        TelegramUpdatePollingService polling = new TelegramUpdatePollingService(jdbc, client, handler);

        polling.poll();
        polling.poll();

        assertEquals(2L, jdbc.queryForObject("select polling_offset from telegram_admin_settings where singleton_id=1", Long.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from telegram_admin_audit where action='orcamento' and outcome='REJECTED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from ai_budget_limits", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from telegram_admin_callback_tokens where consumed_at is not null", Integer.class));

        polling.poll();

        assertEquals(3L, jdbc.queryForObject("select polling_offset from telegram_admin_settings where singleton_id=1", Long.class));
        assertEquals(3, jdbc.queryForObject("select count(*) from outbox_actions where action_type='telegram_admin_reply'", Integer.class));
    }

    @Test void invalidDatesAreRejectedAndDoNotBlockEmergency() {
        Clock clock = Clock.systemUTC();
        var manager = new DataSourceTransactionManager(source);
        var callbacks = new CallbackTokenService(jdbc, clock);
        var commands = new AdminCommandService(jdbc, manager, callbacks, new NotificationOutboxRepository(jdbc, manager), clock);
        var handler = new TelegramUpdateHandler(1001, 2002, commands, callbacks);
        jdbc.update("delete from availability_overrides");
        jdbc.update("delete from holiday_calendar");
        jdbc.update("update operational_restrictions set emergency_latched=false where singleton_id=1");
        TelegramUpdatesClient client = offset -> {
            if (offset == 0) return List.of(messageUpdate(0, "/ferias invalid 2026-12-31"));
            if (offset == 2) return List.of(messageUpdate(2, "/feriado 2026-99-99 BR https://fonte.invalid Teste"));
            if (offset == 1 || offset == 3) {
                String payload = jdbc.queryForObject("select payload::text from outbox_actions where action_type='telegram_admin_reply' order by id desc limit 1", String.class);
                Matcher token = Pattern.compile("/confirm ([A-Za-z0-9_-]+)").matcher(payload);
                if (!token.find()) throw new AssertionError("confirmation token missing");
                return List.of(messageUpdate((int) offset, "/confirm " + token.group(1)));
            }
            if (offset == 4) return List.of(messageUpdate(4, "/emergencia"));
            return List.of();
        };
        var polling = new TelegramUpdatePollingService(jdbc, client, handler);
        for (int i = 0; i < 4; i++) {
            polling.poll();
            if (i == 1 || i == 3) assertEquals(1, jdbc.queryForObject(
                    "select count(*) from telegram_admin_callback_tokens where consumed_at is not null", Integer.class));
        }
        assertEquals(4L, jdbc.queryForObject("select polling_offset from telegram_admin_settings where singleton_id=1", Long.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from telegram_admin_audit where action in ('ferias','feriado') and outcome='REJECTED'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from availability_overrides", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from holiday_calendar", Integer.class));
        // Issuing the second confirmation prunes the first consumed token.
        assertEquals(1, jdbc.queryForObject("select count(*) from telegram_admin_callback_tokens where consumed_at is not null", Integer.class));
        polling.poll();
        assertEquals(5L, jdbc.queryForObject("select polling_offset from telegram_admin_settings where singleton_id=1", Long.class));
        assertEquals(true, jdbc.queryForObject("select emergency_latched from operational_restrictions where singleton_id=1", Boolean.class));
    }

    private Update messageUpdate(int updateId, String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        Chat chat = mock(Chat.class);
        when(update.updateId()).thenReturn(updateId);
        when(update.message()).thenReturn(message);
        when(message.text()).thenReturn(text);
        when(message.from()).thenReturn(user);
        when(user.id()).thenReturn(1001L);
        when(message.chat()).thenReturn(chat);
        when(chat.id()).thenReturn(2002L);
        when(chat.type()).thenReturn(java.util.Arrays.stream(Chat.Type.values())
                .filter(type -> type.name().equalsIgnoreCase("private") || type.name().equalsIgnoreCase("private_"))
                .findFirst().orElseThrow());
        return update;
    }
}
