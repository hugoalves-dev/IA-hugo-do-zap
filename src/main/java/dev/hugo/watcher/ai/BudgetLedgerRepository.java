package dev.hugo.watcher.ai;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL-native budget ledger access. All calls participate in the caller's
 * REQUIRES_NEW transaction; advisory locks and FOR UPDATE are never separated
 * from the reservation/settlement mutations.
 */
@Repository
public class BudgetLedgerRepository {
    private final JdbcTemplate jdbc;

    public BudgetLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialize reservations and settlements even if no limits row exists. */
    public void lockLedger() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(947221, 20)")) {
                statement.execute();
            }
            return null;
        });
        jdbc.query("select singleton_id from ai_budget_limits where singleton_id=1 for update", rs -> { });
    }

    public List<Limits> limitsForUpdate() {
        return jdbc.query("""
                select daily_limit_micros,monthly_limit_micros from ai_budget_limits
                 where singleton_id=1 for update
                """, (rs, row) -> new Limits((Long) rs.getObject(1), (Long) rs.getObject(2)));
    }

    public long usedDaily(LocalDate day) {
        Long sum = jdbc.queryForObject("""
                select coalesce(sum(reserved_micros),0) from ai_budget_reservations
                 where reservation_day=? and status <> 'RELEASED'
                """, Long.class, day);
        return sum == null ? 0L : sum;
    }

    public long usedMonthly(LocalDate month) {
        Long sum = jdbc.queryForObject("""
                select coalesce(sum(reserved_micros),0) from ai_budget_reservations
                 where reservation_month=? and status <> 'RELEASED'
                """, Long.class, month);
        return sum == null ? 0L : sum;
    }

    public void insertReservation(UUID id, long estimatedMicros, LocalDate day, LocalDate month, Instant at) {
        jdbc.update("""
                insert into ai_budget_reservations(reservation_id,estimated_micros,reserved_micros,
                    reservation_day,reservation_month,status,created_at,updated_at)
                values (?,?,?,?,?,'RESERVED',?,?)
                """, id, estimatedMicros, estimatedMicros, day, month,
                Timestamp.from(at), Timestamp.from(at));
    }

    public List<Existing> reservationForUpdate(UUID id) {
        return jdbc.query("""
                select status,reserved_micros,actual_micros from ai_budget_reservations
                 where reservation_id=? for update
                """, (rs, row) -> new Existing(rs.getString(1), rs.getLong(2),
                (Long) rs.getObject(3)), id);
    }

    public void transition(UUID id, String target, long reservedMicros, Long actualMicros) {
        jdbc.update("""
                update ai_budget_reservations set status=?,reserved_micros=?,actual_micros=?,updated_at=now()
                 where reservation_id=?
                """, target, reservedMicros, actualMicros, id);
    }

    public record Limits(Long dailyMicros, Long monthlyMicros) { }
    public record Existing(String status, long reservedMicros, Long actualMicros) { }
}
