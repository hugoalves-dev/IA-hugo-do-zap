package dev.hugo.watcher.notification;

import dev.hugo.watcher.OutboxAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read-only report projections and the shared PostgreSQL transaction lock. */
@Repository
public class ReportQueryRepository {
    private final JdbcTemplate jdbc;

    public ReportQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockDailyReport() {
        jdbc.query("select pg_advisory_xact_lock(947221, 41)", rs -> { });
    }

    public List<OutboxAction> findOutbox(String key) {
        return jdbc.query("""
                select id,action_key,action_type,payload::text,pending_snapshot::text,attempts,created_at,sensitive_expires_at
                  from outbox_actions where action_key=?
                """, (rs, row) -> mapOutbox(rs), key);
    }

    public OutboxAction requireOutbox(String key) {
        return jdbc.queryForObject("""
                select id,action_key,action_type,payload::text,pending_snapshot::text,attempts,created_at,sensitive_expires_at
                  from outbox_actions where action_key=?
                """, (rs, row) -> mapOutbox(rs), key);
    }

    private static OutboxAction mapOutbox(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OutboxAction(rs.getLong("id"), rs.getString("action_key"),
                rs.getString("action_type"), rs.getString("payload"),
                rs.getString("pending_snapshot"), rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("sensitive_expires_at").toInstant(), null);
    }

    public Counts decisionCounts(Instant start, Instant end) {
        return jdbc.queryForObject("""
                select count(*) as decisions,
                       count(*) filter (where would_alert) as alerts,
                       count(*) filter (where would_reply) as replies,
                       count(*) filter (where decision_state='BLOCKED') as blocked,
                       count(*) filter (where production_mode and real_action_allowed) as real_authorized
                  from simulation_decisions where decided_at>=? and decided_at<?
                """, (rs, row) -> new Counts(rs.getLong("decisions"), rs.getLong("alerts"),
                rs.getLong("replies"), rs.getLong("blocked"), rs.getLong("real_authorized")),
                Timestamp.from(start), Timestamp.from(end));
    }

    public TriageCounts triageCounts(Instant start, Instant end) {
        return jdbc.queryForObject("""
                select count(*) filter (where triage_outcome='IGNORE') as ignore_count,
                       count(*) filter (where triage_outcome='NOTIFY_ONLY') as notify_only_count,
                       count(*) filter (where triage_outcome='NOTIFY_AND_REPLY') as notify_reply_count,
                       count(*) filter (where triage_outcome='UNDECIDED') as undecided_count,
                       count(*) filter (where notification_recommended) as notification_recommendations,
                       count(*) filter (where reply_recommended) as reply_recommendations
                  from simulation_decisions where decided_at>=? and decided_at<?
                """, (rs, row) -> new TriageCounts(rs.getLong("ignore_count"), rs.getLong("notify_only_count"),
                rs.getLong("notify_reply_count"), rs.getLong("undecided_count"),
                rs.getLong("notification_recommendations"), rs.getLong("reply_recommendations")),
                Timestamp.from(start), Timestamp.from(end));
    }

    public Map<String, Object> urgencyCounts(Instant start, Instant end) {
        return jdbc.queryForMap("""
                select count(*) filter (where classification_urgency='LOW') as low,
                       count(*) filter (where classification_urgency='NORMAL') as normal,
                       count(*) filter (where classification_urgency='HIGH') as high,
                       count(*) filter (where classification_urgency='CRITICAL') as critical,
                       count(*) filter (where classification_urgency='UNCERTAIN') as uncertain,
                       count(*) filter (where classification_status='UNCERTAIN') as uncertain_count,
                       count(*) filter (where classification_status='UNAVAILABLE') as unavailable_count
                  from simulation_decisions where decided_at>=? and decided_at<?
                """, Timestamp.from(start), Timestamp.from(end));
    }

    public Costs budgetCosts(Instant start, Instant end) {
        return jdbc.queryForObject("""
                select coalesce(sum(reserved_micros),0) as reserved,
                       coalesce(sum(actual_micros),0) as actual
                  from ai_budget_reservations where created_at>=? and created_at<? and status<>'RELEASED'
                """, (rs, row) -> new Costs(rs.getLong("reserved"), rs.getLong("actual")),
                Timestamp.from(start), Timestamp.from(end));
    }

    public record Counts(long decisions, long alerts, long replies, long blocked, long realAuthorized) { }
    public record TriageCounts(long ignore, long notifyOnly, long notifyAndReply, long undecided,
                               long notificationRecommendations, long replyRecommendations) { }
    public record Costs(long reserved, long actual) { }
}
