package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression guard: claims, lease checks and expiry scrubbing belong to a repository. */
class EvolutionPersistenceBoundaryTest {
    @Test
    void evolutionJobProcessorDoesNotOwnJDBC() {
        for (var field : EvolutionJobProcessor.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "EvolutionJobProcessor owns a JDBC field: " + field.getName());
        }
    }
}
