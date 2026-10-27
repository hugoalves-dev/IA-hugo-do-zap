package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;
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
}
