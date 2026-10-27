package dev.hugo.watcher.ai;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Serializes global daily and monthly reservations in PostgreSQL. Amounts are USD micros. */
@Service
public final class BudgetService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;

    public BudgetService(JdbcTemplate jdbc, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.metrics = Objects.requireNonNull(metrics);
    }

    public Reservation reserve(EstimatedCost estimatedCost, Instant at) {
        Objects.requireNonNull(estimatedCost, "estimatedCost");
        Objects.requireNonNull(at, "at");
        LocalDate day = at.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate month = day.withDayOfMonth(1);
        Reservation reservation;
        try {
            reservation = Objects.requireNonNull(transactions.execute(status -> reserveTransaction(estimatedCost, day, month, at)));
        } catch (BudgetExceededException blocked) {
            metrics.counter("ai.budget.reservations", "outcome", "blocked").increment();
            throw blocked;
        }
        metrics.counter("ai.budget.reservations", "outcome", "reserved").increment();
        return reservation;
    }

    private Reservation reserveTransaction(EstimatedCost estimate, LocalDate day, LocalDate month, Instant at) {
        List<Limits> limits = jdbc.query("""
                select daily_limit_micros,monthly_limit_micros from ai_budget_limits
                 where singleton_id=1 for update
                """, (rs, row) -> new Limits((Long) rs.getObject(1), (Long) rs.getObject(2)));
        if (limits.isEmpty() || limits.getFirst().dailyMicros() == null || limits.getFirst().monthlyMicros() == null)
            throw new BudgetExceededException();
        Limits current = limits.getFirst();
        long dailyUsed = used("reservation_day", day);
        long monthlyUsed = used("reservation_month", month);
        if (exceeds(dailyUsed, estimate.micros(), current.dailyMicros())
                || exceeds(monthlyUsed, estimate.micros(), current.monthlyMicros()))
            throw new BudgetExceededException();

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into ai_budget_reservations(reservation_id,estimated_micros,reserved_micros,
                    reservation_day,reservation_month,status,created_at,updated_at)
                values (?,?,?,?,?,'RESERVED',?,?)
                """, id, estimate.micros(), estimate.micros(), day, month, Timestamp.from(at), Timestamp.from(at));
        return new Reservation(id, estimate.micros(), day, month);
    }

    private long used(String dateColumn, LocalDate date) {
        if (!dateColumn.equals("reservation_day") && !dateColumn.equals("reservation_month"))
            throw new IllegalArgumentException("invalid budget period");
        Long result = jdbc.queryForObject("select coalesce(sum(reserved_micros),0) from ai_budget_reservations where "
                + dateColumn + "=? and status <> 'RELEASED'", Long.class, date);
        return result == null ? 0 : result;
    }

    private static boolean exceeds(long used, long requested, long limit) {
        return used > limit || requested > limit - used;
    }

    public void settle(UUID id, ActualCost actualCost) {
        Objects.requireNonNull(actualCost, "actualCost");
        transition(id, "SETTLED", actualCost.micros());
    }

    /** Keep the full estimate counted when provider outcome or billing is ambiguous. */
    public void markUncertain(UUID id) { transition(id, "UNCERTAIN", null); }

    public void release(UUID id) { transition(id, "RELEASED", 0L); }

    private void transition(UUID id, String target, Long amount) {
        Objects.requireNonNull(id, "id");
        transactions.executeWithoutResult(status -> {
            List<Existing> rows = jdbc.query("""
                    select status,reserved_micros,actual_micros from ai_budget_reservations
                     where reservation_id=? for update
                    """, (rs, row) -> new Existing(rs.getString(1), rs.getLong(2), (Long) rs.getObject(3)), id);
            if (rows.isEmpty()) throw new IllegalArgumentException("budget reservation not found");
            Existing existing = rows.getFirst();
            if (existing.status().equals(target)) {
                if (target.equals("SETTLED") && !Objects.equals(existing.actualMicros(), amount))
                    throw new IllegalStateException("reservation already settled with different cost");
                return;
            }
            if (existing.status().equals("SETTLED") || existing.status().equals("RELEASED")) return;
            long reserved = target.equals("SETTLED") ? amount : target.equals("RELEASED") ? 0 : existing.reservedMicros();
            jdbc.update("""
                    update ai_budget_reservations set status=?,reserved_micros=?,actual_micros=?,updated_at=now()
                     where reservation_id=?
                    """, target, reserved, target.equals("SETTLED") ? amount : null, id);
        });
        metrics.counter("ai.budget.reservation.transitions", "outcome", target.toLowerCase(java.util.Locale.ROOT)).increment();
    }

    private record Limits(Long dailyMicros, Long monthlyMicros) { }
    private record Existing(String status, long reservedMicros, Long actualMicros) { }
}
