package dev.hugo.watcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityPersistenceBoundaryTest {
    @Test
    void identityServiceUsesDedicatedMergeRepository() {
        assertTrue(java.util.Arrays.stream(IdentityService.class.getDeclaredFields())
                .anyMatch(field -> field.getType().equals(IdentityMergeRepository.class)));
    }
}
