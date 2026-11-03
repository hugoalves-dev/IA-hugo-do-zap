package dev.hugo.watcher.ai;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** Serializes global daily and monthly reservations in PostgreSQL. Amounts are USD micros. */
public final class BudgetService {
    private final BudgetLedgerRepository ledger;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;

    public BudgetService(BudgetLedgerRepository ledger, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.ledger = Objects.requireNonNull(ledger);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        // Accounting must survive a surrounding simulation transaction rolling back.
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.metrics = Objects.requireNonNull(metrics);
    }

    /** Legacy constructor for standalone PostgreSQL integration tests. */
    public BudgetService(JdbcTemplate jdbc, PlatformTransactionManager manager, MeterRegistry metrics) {
        this(new BudgetLedgerRepository(jdbc), manager, metrics);
    }

    public Reservation reserve(EstimatedCost estimatedCost, Instant at) {
        Objects.requireNonNull(estimatedCost, "estimatedCost");
        Objects.requireNonNull(at, "at");
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate month = day.withDayOfMonth(1);
        Reservation reservation;
        try {
            reservation = Objects.requireNonNull(transactions.execute(status ->
                    reserveTransaction(estimatedCost, day, month, at)));
        } catch (BudgetExceededException blocked) {
            metrics.counter("ai.budget.reservations", "outcome", "blocked").increment();
            throw blocked;
        }
        metrics.counter("ai.budget.reservations", "outcome", "reserved").increment();
        return reservation;
    }

    private Reservation reserveTransaction(EstimatedCost estimate, LocalDate day, LocalDate month, Instant at) {
        ledger.lockLedger();
        var limits = ledger.limitsForUpdate();
        if (limits.isEmpty() || limits.getFirst().dailyMicros() == null || limits.getFirst().monthlyMicros() == null)
            throw new BudgetExceededException();
        var current = limits.getFirst();
        long dailyUsed = ledger.usedDaily(day);
        long monthlyUsed = ledger.usedMonthly(month);
        if (exceeds(dailyUsed, estimate.micros(), current.dailyMicros())
                || exceeds(monthlyUsed, estimate.micros(), current.monthlyMicros()))
            throw new BudgetExceededException();

        UUID id = UUID.randomUUID();
        ledger.insertReservation(id, estimate.micros(), day, month, at);
        return new Reservation(id, estimate.micros(), day, month);
    }

    private static boolean exceeds(long used, long requested, long limit) {
        return used > limit || requested > limit - used;
    }

    public void settle(UUID id, ActualCost actualCost) {
        Objects.requireNonNull(actualCost, "actualCost");
        transition(id, "SETTLED", actualCost.micros());
    }

    /** Persist the point where a provider request may have left the process. */
    public void markDispatched(UUID id) { transition(id, "DISPATCHED", null); }

    /** Keep the full estimate counted when provider outcome or billing is ambiguous. */
    public void markUncertain(UUID id) { transition(id, "UNCERTAIN", null); }

    public void release(UUID id) { transition(id, "RELEASED", 0L); }

    private void transition(UUID id, String target, Long amount) {
        Objects.requireNonNull(id, "id");
        transactions.executeWithoutResult(status -> {
            ledger.lockLedger();
            var rows = ledger.reservationForUpdate(id);
            if (rows.isEmpty()) throw new IllegalArgumentException("budget reservation not found");
            var existing = rows.getFirst();
            if (existing.status().equals(target)) {
                if (target.equals("SETTLED") && !Objects.equals(existing.actualMicros(), amount))
                    throw new IllegalStateException("reservation already settled with different cost");
                return;
            }
            if (target.equals("SETTLED")) {
                if (existing.status().equals("SETTLED")) return;
            } else if (existing.status().equals("SETTLED")) {
                return;
            } else if (target.equals("DISPATCHED")) {
                if (!existing.status().equals("RESERVED"))
                    throw new IllegalStateException("only a reserved call can be dispatched");
            } else if (target.equals("UNCERTAIN")) {
                if (!existing.status().equals("DISPATCHED"))
                    throw new IllegalStateException("only a dispatched call can become uncertain");
            } else if (target.equals("RELEASED")) {
                if (existing.status().equals("RELEASED")) return;
                if (!existing.status().equals("RESERVED"))
                    throw new IllegalStateException("dispatched or uncertain reservation cannot be released");
            }
            long reserved = target.equals("SETTLED") ? amount : target.equals("RELEASED") ? 0 : existing.reservedMicros();
            ledger.transition(id, target, reserved, target.equals("SETTLED") ? amount : null);
        });
        metrics.counter("ai.budget.reservation.transitions", "outcome", target.toLowerCase(java.util.Locale.ROOT)).increment();
    }
}
