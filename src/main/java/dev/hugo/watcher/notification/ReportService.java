package dev.hugo.watcher.notification;

import dev.hugo.watcher.NotificationOutboxRepository;
import dev.hugo.watcher.OutboxAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Creates an aggregate-only daily report in the Telegram outbox. */
@Service
public class ReportService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NotificationOutboxRepository outbox;
    private final Clock clock;

    public ReportService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                         NotificationOutboxRepository outbox, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.outbox = outbox;
        this.clock = clock;
    }

    public OutboxAction schedule(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("report date is required");
        return transactions.execute(status -> {
            jdbc.query("select pg_advisory_xact_lock(947221, 41)", rs -> { });
            String key = "simulation_daily_summary:" + date;
            var existing = jdbc.query("""
                    select id,action_key,action_type,payload::text,pending_snapshot::text,attempts,created_at,sensitive_expires_at
                      from outbox_actions where action_key=?
                    """, (rs, row) -> new OutboxAction(rs.getLong("id"), rs.getString("action_key"),
                    rs.getString("action_type"), rs.getString("payload"), rs.getString("pending_snapshot"),
                    rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("sensitive_expires_at").toInstant(), null), key);
            if (!existing.isEmpty()) return existing.getFirst();

            Instant start = date.atStartOfDay(REPORT_ZONE).toInstant();
            Instant end = date.plusDays(1).atStartOfDay(REPORT_ZONE).toInstant();
            Counts counts = jdbc.queryForObject("""
                    select count(*) as decisions,
                           count(*) filter (where would_alert) as alerts,
                           count(*) filter (where would_reply) as replies,
                           count(*) filter (where decision_state='BLOCKED') as blocked
                      from simulation_decisions where decided_at>=? and decided_at<?
                    """, (rs, row) -> new Counts(rs.getLong("decisions"), rs.getLong("alerts"),
                    rs.getLong("replies"), rs.getLong("blocked")), Timestamp.from(start), Timestamp.from(end));
            Costs costs = jdbc.queryForObject("""
                    select coalesce(sum(reserved_micros),0) as reserved,
                           coalesce(sum(actual_micros),0) as actual
                      from ai_budget_reservations where created_at>=? and created_at<? and status<>'RELEASED'
                    """, (rs, row) -> new Costs(rs.getLong("reserved"), rs.getLong("actual")),
                    Timestamp.from(start), Timestamp.from(end));
            Instant created = clock.instant();
            String payload = JSON.writeValueAsString(Map.of(
                    "eventType", "simulation_daily_summary", "date", date.toString(),
                    "decisionCount", counts.decisions(), "predictedAlerts", counts.alerts(),
                    "predictedReplies", counts.replies(), "blockedDecisions", counts.blocked(),
                    "reservedCostMicros", costs.reserved(), "reportedActualCostMicros", costs.actual()));
            outbox.enqueue(key, "simulation_daily_summary", payload, "[]", created, created.plusSeconds(86_400));
            return jdbc.queryForObject("""
                    select id,action_key,action_type,payload::text,pending_snapshot::text,attempts,created_at,sensitive_expires_at
                      from outbox_actions where action_key=?
                    """, (rs, row) -> new OutboxAction(rs.getLong("id"), rs.getString("action_key"),
                    rs.getString("action_type"), rs.getString("payload"), rs.getString("pending_snapshot"),
                    rs.getInt("attempts"), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("sensitive_expires_at").toInstant(), null), key);
        });
    }

    private record Counts(long decisions, long alerts, long replies, long blocked) { }
    private record Costs(long reserved, long actual) { }
}
