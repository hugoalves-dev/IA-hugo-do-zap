package dev.hugo.watcher.ai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class BudgetServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private BudgetService budgets;
    private final Instant now = Instant.parse("2026-10-27T12:00:00Z");

    @BeforeEach void migrateAndReset() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("delete from ai_budget_reservations");
        jdbc.update("delete from ai_budget_limits");
        budgets = new BudgetService(jdbc, new DataSourceTransactionManager(source), new SimpleMeterRegistry());
    }

    @Test void missingDailyOrMonthlyLimitBlocksReservation() {
        assertThrows(BudgetExceededException.class, () -> budgets.reserve(new EstimatedCost(1), now));
        setLimits(10L, null);
        assertThrows(BudgetExceededException.class, () -> budgets.reserve(new EstimatedCost(1), now));
        assertEquals(0, jdbc.queryForObject("select count(*) from ai_budget_reservations", Integer.class));
    }

    @Test void concurrentReservationsCannotExceedDailyOrMonthlyLimit() throws Exception {
        setLimits(10L, 10L);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = List.of(pool.submit(() -> reserveAfter(start)), pool.submit(() -> reserveAfter(start)));
            start.countDown();
            long accepted = 0;
            for (var attempt : attempts) if (attempt.get()) accepted++;
            assertEquals(1, accepted);
            assertEquals(10L, jdbc.queryForObject("select sum(reserved_micros) from ai_budget_reservations", Long.class));
        }
    }

    @Test void monthlyLimitBlocksReservationsEvenWhenDailyCapacityRemains() {
        setLimits(100L, 10L);
        budgets.reserve(new EstimatedCost(7), now);
        assertThrows(BudgetExceededException.class, () -> budgets.reserve(new EstimatedCost(4), now));
    }

    @Test void failedAmbiguousCallKeepsReservationUntilExplicitReconciliation() {
        setLimits(10L, 10L);
        Reservation reservation = budgets.reserve(new EstimatedCost(7), now);
        assertThrows(RuntimeException.class, () -> invokePaidCallThenReconcileFailure(reservation));
        assertThrows(BudgetExceededException.class, () -> budgets.reserve(new EstimatedCost(4), now));
        budgets.settle(reservation.id(), new ActualCost(6));
        budgets.settle(reservation.id(), new ActualCost(6));
        assertEquals("SETTLED", status(reservation.id()));
        assertEquals(6L, reserved(reservation.id()));
    }

    private void invokePaidCallThenReconcileFailure(Reservation reservation) {
        try {
            throw new RuntimeException("ambiguous provider timeout");
        } catch (RuntimeException ambiguous) {
            budgets.markUncertain(reservation.id());
            throw ambiguous;
        }
    }

    @Test void settleAndReleaseAreIdempotentAndDoNotEraseActualCost() {
        setLimits(10L, 10L);
        Reservation settled = budgets.reserve(new EstimatedCost(4), now);
        budgets.settle(settled.id(), new ActualCost(5));
        budgets.settle(settled.id(), new ActualCost(5));
        budgets.release(settled.id());
        assertEquals("SETTLED", status(settled.id()));
        assertEquals(5L, reserved(settled.id()));

        Reservation released = budgets.reserve(new EstimatedCost(4), now);
        budgets.release(released.id());
        budgets.release(released.id());
        assertEquals("RELEASED", status(released.id()));
        assertEquals(0L, reserved(released.id()));
    }

    private boolean reserveAfter(CountDownLatch start) throws Exception {
        start.await();
        try { budgets.reserve(new EstimatedCost(10), now); return true; }
        catch (BudgetExceededException blocked) { return false; }
    }

    private void setLimits(Long daily, Long monthly) {
        jdbc.update("insert into ai_budget_limits(singleton_id,daily_limit_micros,monthly_limit_micros) values (1,?,?)", daily, monthly);
    }
    private String status(UUID id) { return jdbc.queryForObject("select status from ai_budget_reservations where reservation_id=?", String.class, id); }
    private long reserved(UUID id) { return jdbc.queryForObject("select reserved_micros from ai_budget_reservations where reservation_id=?", Long.class, id); }
}
