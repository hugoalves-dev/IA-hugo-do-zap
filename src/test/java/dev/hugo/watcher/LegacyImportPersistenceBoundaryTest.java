package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyImportPersistenceBoundaryTest {
    @Test
    void importServiceKeepsSqlOutsideOrchestration() {
        assertTrue(java.util.Arrays.stream(LegacyImportService.class.getDeclaredFields())
                .anyMatch(f -> f.getType().equals(LegacyImportPersistenceRepository.class)));
        for (var f : LegacyImportService.class.getDeclaredFields())
            assertFalse(JdbcTemplate.class.isAssignableFrom(f.getType()), () -> "LegacyImportService owns JDBC: " + f.getName());
    }
}
