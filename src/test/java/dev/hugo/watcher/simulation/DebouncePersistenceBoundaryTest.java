package dev.hugo.watcher.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebouncePersistenceBoundaryTest {
    @Test
    void debounceOrchestratesWithoutJdbcField() {
        assertTrue(java.util.Arrays.stream(DebounceService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(DebouncePersistenceRepository.class)));
        for (var field : DebounceService.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "DebounceService owns JDBC: " + field.getName());
        }
    }
}
