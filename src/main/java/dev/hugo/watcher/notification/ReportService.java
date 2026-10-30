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

    /** Automatic daily delivery stays idempotent for each reporting date. */
    public OutboxAction schedule(LocalDate date) {
        return enqueue(date, "simulation_daily_summary:" + date);
    }

    /** A private administrator command needs a fresh snapshot on each request. */
    public OutboxAction scheduleOnDemand(LocalDate date) {
        return enqueue(date, "simulation_daily_summary:manual:" + date + ":" + java.util.UUID.randomUUID());
    }

    private OutboxAction enqueue(LocalDate date, String key) {
        if (date == null) throw new IllegalArgumentException("report date is required");
        return transactions.execute(status -> {
            jdbc.query("select pg_advisory_xact_lock(947221, 41)", rs -> { });
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
            var distribution = jdbc.queryForMap("""
                    select count(*) filter (where classification_urgency='LOW') as low,
                           count(*) filter (where classification_urgency='NORMAL') as normal,
                           count(*) filter (where classification_urgency='HIGH') as high,
                           count(*) filter (where classification_urgency='CRITICAL') as critical,
                           count(*) filter (where classification_urgency='UNCERTAIN') as uncertain,
                           count(*) filter (where classification_status='UNCERTAIN') as uncertain_count,
                           count(*) filter (where classification_status='UNAVAILABLE') as unavailable_count
                      from simulation_decisions where decided_at>=? and decided_at<?
                    """, Timestamp.from(start), Timestamp.from(end));
            Costs costs = jdbc.queryForObject("""
                    select coalesce(sum(reserved_micros),0) as reserved,
                           coalesce(sum(actual_micros),0) as actual
                      from ai_budget_reservations where created_at>=? and created_at<? and status<>'RELEASED'
                    """, (rs, row) -> new Costs(rs.getLong("reserved"), rs.getLong("actual")),
                    Timestamp.from(start), Timestamp.from(end));
            Instant created = clock.instant();
            String payload = JSON.writeValueAsString(Map.ofEntries(
                    Map.entry("eventType", "simulation_daily_summary"), Map.entry("date", date.toString()),
                    Map.entry("decisionCount", counts.decisions()), Map.entry("predictedAlerts", counts.alerts()),
                    Map.entry("predictedReplies", counts.replies()), Map.entry("blockedDecisions", counts.blocked()),
                    Map.entry("reservedCostMicros", costs.reserved()), Map.entry("reportedActualCostMicros", costs.actual()),
                    Map.entry("urgencyDistribution", Map.of("LOW",distribution.get("low"), "NORMAL",distribution.get("normal"),
                            "HIGH",distribution.get("high"), "CRITICAL",distribution.get("critical"), "UNCERTAIN",distribution.get("uncertain"))),
                    Map.entry("uncertainCount", distribution.get("uncertain_count")),
                    Map.entry("unavailableCount", distribution.get("unavailable_count"))));
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
