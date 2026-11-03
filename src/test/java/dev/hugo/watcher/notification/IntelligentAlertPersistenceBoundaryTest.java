package dev.hugo.watcher.notification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;

class IntelligentAlertPersistenceBoundaryTest {
    @Test
    void intelligentAlertServiceDoesNotOwnJdbc() {
        for (var field : IntelligentAlertService.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "IntelligentAlertService owns a JDBC field: " + field.getName());
        }
    }
}
