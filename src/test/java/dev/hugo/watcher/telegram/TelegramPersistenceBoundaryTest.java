package dev.hugo.watcher.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Issue #24: keep direct SQL and JdbcTemplate out of the Telegram HTTP/worker/service layer. */
class TelegramPersistenceBoundaryTest {
    @Test void telegramHandlersKeepPersistenceInRepositories() {
        for (Class<?> type : new Class<?>[] {
                TelegramWebhookController.class, TelegramWebhookWorker.class, CallbackTokenService.class
        }) {
            for (var field : type.getDeclaredFields()) {
                assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                        () -> type.getSimpleName() + " has a JDBC field: " + field.getName());
            }
        }
    }
}
