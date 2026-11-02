package dev.hugo.watcher.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SimulationSchedulerPersistenceTest {
    @Test void pollingSchedulerReadsDueTimesViaRepository() {
        for (var field : SimulationScheduler.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "SimulationScheduler directly accesses JDBC via " + field.getName());
        }
    }
}
