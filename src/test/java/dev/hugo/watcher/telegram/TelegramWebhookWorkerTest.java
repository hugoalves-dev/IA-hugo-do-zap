package dev.hugo.watcher.telegram;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class TelegramWebhookWorkerTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TelegramUpdateHandler handler;
    private TelegramCallbackAcknowledger callbacks;

    @BeforeEach void setup() {
        var datasource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(datasource).load().migrate();
        jdbc = new JdbcTemplate(datasource);
        jdbc.update("delete from telegram_webhook_inbox");
        manager = new DataSourceTransactionManager(datasource);
        handler = mock(TelegramUpdateHandler.class);
        callbacks = mock(TelegramCallbackAcknowledger.class);
    }

    @Test void processesOnceEvenWhenSchedulerRunsAgain() {
        when(handler.handle(any())).thenReturn(AdminResult.accepted("ok"));
        jdbc.update("insert into telegram_webhook_inbox(update_id,payload) values(?,?::jsonb)",
                101L, "{\"update_id\":101,\"message\":{\"message_id\":1,\"chat\":{\"id\":2002,\"type\":\"private\"},\"from\":{\"id\":1001},\"text\":\"/status\"}}");
        var worker = new TelegramWebhookWorker(jdbc, manager, handler, callbacks);
        worker.drain();
        worker.drain();
        assertEquals("DONE", jdbc.queryForObject("select status from telegram_webhook_inbox where update_id=101", String.class));
        assertEquals("{}", jdbc.queryForObject("select payload::text from telegram_webhook_inbox where update_id=101", String.class));
        verify(handler, times(1)).handle(any());
        verifyNoInteractions(callbacks);
    }

    @Test void callbackIsAcknowledgedOnlyAfterCommittedHandling() {
        when(handler.handle(any())).thenReturn(AdminResult.accepted("ok"));
        jdbc.update("insert into telegram_webhook_inbox(update_id,payload) values(?,?::jsonb)",
                102L, "{\"update_id\":102,\"callback_query\":{\"id\":\"cb-102\",\"from\":{\"id\":1001},\"message\":{\"message_id\":2,\"chat\":{\"id\":2002,\"type\":\"private\"}},\"data\":\"opaque\"}}");
        new TelegramWebhookWorker(jdbc, manager, handler, callbacks).drain();
        assertEquals("DONE", jdbc.queryForObject("select status from telegram_webhook_inbox where update_id=102", String.class));
        verify(callbacks).answer(eq("cb-102"), anyString());
    }

    @Test void handlerFailureDoesNotMarkUpdateDone() {
        when(handler.handle(any())).thenThrow(new IllegalStateException("simulated failure"));
        jdbc.update("insert into telegram_webhook_inbox(update_id,payload) values(?,?::jsonb)",
                103L, "{\"update_id\":103,\"message\":{\"message_id\":1,\"chat\":{\"id\":2002,\"type\":\"private\"},\"from\":{\"id\":1001},\"text\":\"/status\"}}");
        new TelegramWebhookWorker(jdbc, manager, handler, callbacks).drain();
        assertEquals("PENDING", jdbc.queryForObject("select status from telegram_webhook_inbox where update_id=103", String.class));
        assertEquals(1, jdbc.queryForObject("select attempts from telegram_webhook_inbox where update_id=103", Integer.class));
        verifyNoInteractions(callbacks);
    }
}
