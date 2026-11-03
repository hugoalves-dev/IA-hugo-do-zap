package dev.hugo.watcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.springframework.jdbc.core.JdbcTemplate;

class IdentityPersistenceBoundaryTest {
    @Test
    void identityServiceUsesDedicatedMergeRepository() {
        assertTrue(java.util.Arrays.stream(IdentityService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(IdentityMergeRepository.class)));
        for (var field : IdentityService.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "IdentityService still owns JDBC: " + field.getName());
        }
    }
}
