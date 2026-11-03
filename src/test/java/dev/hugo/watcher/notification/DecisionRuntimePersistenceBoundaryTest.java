package dev.hugo.watcher.notification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DecisionRuntimePersistenceBoundaryTest {
    @Test
    void runtimeGateDoesNotOwnJdbcField() {
        for (var field : DecisionRuntimeGate.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "DecisionRuntimeGate owns a JDBC field: " + field.getName());
        }
    }
}
