package dev.hugo.watcher;

import dev.hugo.watcher.policy.GlobalPolicyRepository;
import dev.hugo.watcher.policy.HolidayService;
import dev.hugo.watcher.policy.PersistedPolicyRepository;
import dev.hugo.watcher.notification.ReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression guard for review #5740068169: constructor wiring is explicit, not @Autowired. */
class PersistenceConstructorWiringTest {
    @Test
    void persistenceServicesDoNotAnnotateConstructorsForWiring() {
        for (Class<?> service : new Class<?>[] {
                ArchiveModeService.class,
                ConnectionService.class,
                HolidayService.class,
                PersistedPolicyRepository.class,
                GlobalPolicyRepository.class,
                OperationalState.class,
                RetentionService.class,
                ReportService.class
        }) {
            for (var constructor : service.getDeclaredConstructors()) {
                assertFalse(constructor.isAnnotationPresent(Autowired.class),
                        () -> service.getName() + " uses @Autowired on a constructor");
            }
        }
    }

    @Test
    void dailyReportServiceDoesNotOwnJdbcQueries() {
        for (var field : ReportService.class.getDeclaredFields()) {
            assertFalse(JdbcTemplate.class.isAssignableFrom(field.getType()),
                    () -> "ReportService still owns JDBC field " + field.getName());
        }
    }
}
