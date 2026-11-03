package dev.hugo.watcher.notification;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** The real-action eligibility check must be repository-owned. */
class DecisionActionProducerPersistenceTest {
    @Test void productionEligibilityQueryIsEncapsulated() {
        for (var field : DecisionActionProducer.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "DecisionActionProducer directly accesses JDBC via " + field.getName());
        }
    }
}
