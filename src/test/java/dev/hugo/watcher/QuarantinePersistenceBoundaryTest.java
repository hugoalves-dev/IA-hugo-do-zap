package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class QuarantinePersistenceBoundaryTest {
    @Test
    void quarantineStorageIsARepository() {
        assertNotNull(QuarantineEventRepository.class.getAnnotation(Repository.class));
    }
}
