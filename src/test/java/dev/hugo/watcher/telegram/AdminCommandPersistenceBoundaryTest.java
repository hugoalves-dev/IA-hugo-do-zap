package dev.hugo.watcher.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;

class AdminCommandPersistenceBoundaryTest {
    @Test
    void adminCommandsDoNotOwnJdbcField() {
        for (var field : AdminCommandService.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "AdminCommandService owns JDBC: " + field.getName());
        }
    }
}
