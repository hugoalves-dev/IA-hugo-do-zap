package dev.hugo.watcher.simulation;

import dev.hugo.watcher.ArchiveMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class SimulationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final WatcherSimulationProperties properties;

    @Autowired
    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                             WatcherSimulationProperties properties) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
        this.properties = properties;
    }

    public SimulationService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC(), new WatcherSimulationProperties());
    }

    public SimulationService(JdbcTemplate jdbc) {
        this(jdbc, new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public SimulationDecision evaluate(Batch batch) {
        Objects.requireNonNull(batch, "batch");
        return Objects.requireNonNull(transactions.execute(status -> evaluateTransaction(batch, clock.instant())));
    }

    private SimulationDecision evaluateTransaction(Batch batch, Instant now) {
        List<Scope> scope = jdbc.query("""
                select settings.mode, snapshot.archived, coalesce(snapshot.synchronized_snapshot,false) synchronized_snapshot
                  from archive_monitor_settings settings
                  left join chat_snapshot snapshot on snapshot.chat_jid=?
                 where settings.singleton_id=1
                """, (rs, row) -> new Scope(ArchiveMode.valueOf(rs.getString("mode")), (Boolean) rs.getObject("archived"),
                rs.getBoolean("synchronized_snapshot")), batch.chatJid());
        if (scope.isEmpty()) throw new IllegalStateException("archive monitor settings missing");
        Scope current = scope.getFirst();
        String blocked = blockedReason(current);
        String reason = blocked == null ? "decision_engine_pending_task_3" : "archive_scope_blocked";
        SimulationDecision decision = new SimulationDecision(false, false, null, reason, blocked);
        jdbc.update("""
                insert into simulation_decisions(batch_id,would_alert,would_reply,template_id,decision_reason,blocked_reason,decided_at,metadata_expires_at)
                values (?,?,?,?,?,?,?,?) on conflict (batch_id) do nothing
                """, batch.id(), decision.wouldAlert(), decision.wouldReply(), decision.templateId(), decision.decisionReason(),
                decision.blockedReason(), Timestamp.from(now), Timestamp.from(now.plusSeconds(properties.getAuditRetentionDays() * 86_400L)));
        List<SimulationDecision> persisted = jdbc.query("""
                select would_alert,would_reply,template_id,decision_reason,blocked_reason
                  from simulation_decisions where batch_id=?
                """, (rs, row) -> new SimulationDecision(rs.getBoolean("would_alert"), rs.getBoolean("would_reply"),
                rs.getString("template_id"), rs.getString("decision_reason"), rs.getString("blocked_reason")), batch.id());
        if (persisted.isEmpty()) throw new IllegalStateException("simulation decision was not persisted");
        jdbc.update("update simulation_batches set status='COMPLETED', claimed_at=null where batch_id=? and status='CLAIMED'", batch.id());
        return persisted.getFirst();
    }

    private static String blockedReason(Scope scope) {
        if (scope.mode() == ArchiveMode.ALL) return null;
        if (scope.archived() == null || !scope.synchronizedSnapshot()) return "archive_state_unknown";
        if (!scope.mode().accepts(scope.archived(), scope.synchronizedSnapshot())) return "archive_scope_excluded";
        return null;
    }

    private record Scope(ArchiveMode mode, Boolean archived, boolean synchronizedSnapshot) { }
}
