package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Connection alerts, pending queue projection and destructive queue updates belong in repositories. */
class NotificationPersistenceBoundaryTest {
    @Test
    void notificationCoordinatorDoesNotOwnJDBC() {
        for (var field : NotificationService.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "NotificationService owns a JDBC field: " + field.getName());
        }
    }
}
