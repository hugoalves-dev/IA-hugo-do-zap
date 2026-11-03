package dev.hugo.watcher.notification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dispatch authorization must own no SQL at the service boundary. */
class DecisionDeliveryPersistenceBoundaryTest {
    @Test
    void coordinatorKeepsTransactionalOrchestrationButNotJdbcFields() {
        for (var field : DecisionDeliveryCoordinator.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "DecisionDeliveryCoordinator owns a JDBC field: " + field.getName());
        }
        assertTrue(java.util.Arrays.stream(DecisionDeliveryCoordinator.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(DeliveryAdmissionRepository.class)));
    }
}
