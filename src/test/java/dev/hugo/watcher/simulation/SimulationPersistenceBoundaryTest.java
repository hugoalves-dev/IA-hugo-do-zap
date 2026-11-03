package dev.hugo.watcher.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationPersistenceBoundaryTest {
    @Test
    void simulationServiceOwnsNoJdbcField() {
        assertTrue(java.util.Arrays.stream(SimulationService.class.getDeclaredFields())
                .anyMatch(f -> f.getType().equals(SimulationPersistenceRepository.class)));
        for(var field : SimulationService.class.getDeclaredFields())
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "SimulationService owns JDBC: " + field.getName());
    }
}
