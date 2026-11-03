package dev.hugo.watcher.notification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatsAppActionPersistenceBoundaryTest {
    @Test
    void serviceDoesNotPerformPersistenceDirectly() {
        assertTrue(java.util.Arrays.stream(WhatsAppActionService.class.getDeclaredFields())
                .anyMatch(f -> f.getType().equals(WhatsAppActionPersistenceRepository.class)));
        for (var field : WhatsAppActionService.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "WhatsAppActionService owns JDBC: " + field.getName());
        }
    }
}
