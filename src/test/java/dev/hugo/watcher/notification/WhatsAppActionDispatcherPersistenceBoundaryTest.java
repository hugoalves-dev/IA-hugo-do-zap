package dev.hugo.watcher.notification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatsAppActionDispatcherPersistenceBoundaryTest {
    @Test
    void dispatcherDoesNotOwnJdbc() {
        assertTrue(java.util.Arrays.stream(WhatsAppActionDispatcher.class.getDeclaredFields())
                .anyMatch(f -> f.getType().equals(WhatsAppActionDispatchRepository.class)));
        for (var field : WhatsAppActionDispatcher.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "WhatsAppActionDispatcher still owns JDBC: " + field.getName());
        }
    }
}
