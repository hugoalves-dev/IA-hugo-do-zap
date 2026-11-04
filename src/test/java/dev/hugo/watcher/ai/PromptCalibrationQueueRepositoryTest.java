package dev.hugo.watcher.ai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PromptCalibrationQueueRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private PromptCalibrationQueueRepository queue;

    @BeforeEach void migrate() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        queue = new PromptCalibrationQueueRepository(jdbc, new DataSourceTransactionManager(source));
    }

    @Test void confirmedCalibrationIsClaimedOnceAndPersistsItsResult() {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        var id = queue.enqueue(0, 1001, 2002, now);
        var claimed = queue.claimPending(now.plusSeconds(1));
        assertEquals(id, claimed.id());
        assertEquals("DISPATCHED", jdbc.queryForObject("select status from prompt_calibration_runs where run_id=?", String.class, id));
        assertNull(queue.claimPending(now.plusSeconds(2)));

        assertTrue(queue.finish(id, "COMPLETE", "Synthetic classification result.", now.plusSeconds(3)));
        assertEquals("COMPLETE", jdbc.queryForObject("select status from prompt_calibration_runs where run_id=?", String.class, id));
        assertEquals("Synthetic classification result.", jdbc.queryForObject("select result_summary from prompt_calibration_runs where run_id=?", String.class, id));
    }

    @Test void rateLimitAllowsThreeTestsPerAdminPerHourAndRejectsTheFourth() {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        for (int i = 0; i < 3; i++) queue.enqueue(0, 1001, 2002, now.plusSeconds(i));
        assertThrows(IllegalArgumentException.class, () -> queue.enqueue(0, 1001, 2002, now.plusSeconds(4)));
        assertEquals(3, queue.count("PENDING"));
    }

    @Test void interruptedDispatchedTestIsMarkedUnknownAndNeverRequeued() {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        var id = queue.enqueue(0, 1001, 2002, now);
        queue.claimPending(now.plusSeconds(1));
        var stale = queue.staleDispatched(now.plusSeconds(2));
        assertEquals(1, stale.size());
        assertTrue(queue.finishUnknown(stale.getFirst(), "unknown", now.plusSeconds(3), now.plusSeconds(2)));
        assertEquals("UNKNOWN", jdbc.queryForObject("select status from prompt_calibration_runs where run_id=?", String.class, id));
        assertNull(queue.claimPending(now.plusSeconds(4)));
    }
}
