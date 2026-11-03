package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSimulationPersistenceBoundaryTest {
    @Test
    void classifierOrchestratesWithoutJdbcFields() {
        assertTrue(java.util.Arrays.stream(AiSimulationClassifier.class.getDeclaredFields())
                .anyMatch(f -> f.getType().equals(AiClassificationEvaluationRepository.class)));
        for (var f : AiSimulationClassifier.class.getDeclaredFields())
            assertFalse(JdbcTemplate.class.isAssignableFrom(f.getType()),
                    () -> "AiSimulationClassifier owns JDBC: " + f.getName());
    }
}
