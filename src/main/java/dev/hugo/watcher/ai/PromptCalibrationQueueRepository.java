package dev.hugo.watcher.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

/** Durable, rate-limited queue for explicit prompt-test requests. Provider calls happen in the worker after claim commits. */
@Repository
public class PromptCalibrationQueueRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PromptCalibrationQueueRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public UUID enqueue(int revision, long actor, long chat, Instant now) {
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    statement.setString(1, "prompt-calibration:" + actor);
                    statement.execute();
                }
                return null;
            });
            Integer recent = jdbc.queryForObject("select count(*) from prompt_calibration_runs where actor_user_id=? and created_at>?",
                    Integer.class, actor, Timestamp.from(now.minusSeconds(3600)));
            if (recent != null && recent >= 3) throw new IllegalArgumentException("Limite de 3 testes de prompt por hora atingido.");
            UUID id = UUID.randomUUID();
            jdbc.update("insert into prompt_calibration_runs(run_id,prompt_revision,actor_user_id,chat_id,status,created_at) values (?,?,?,?,'PENDING',?)",
                    id, revision, actor, chat, Timestamp.from(now));
            return id;
        }));
    }

    public PromptCalibrationRun claimPending(Instant now) {
        return transactions.execute(status -> {
            List<PromptCalibrationRun> rows = jdbc.query("""
                    with candidate as (
                        select run_id from prompt_calibration_runs where status='PENDING'
                         order by created_at,run_id limit 1 for update skip locked
                    )
                    update prompt_calibration_runs r set status='DISPATCHED',started_at=?
                     from candidate c where r.run_id=c.run_id
                    returning r.run_id,r.prompt_revision,r.actor_user_id,r.chat_id,r.created_at
                    """, (rs, row) -> new PromptCalibrationRun(rs.getObject("run_id", UUID.class),
                    rs.getInt("prompt_revision"), rs.getLong("actor_user_id"), rs.getLong("chat_id"),
                    rs.getTimestamp("created_at").toInstant()), Timestamp.from(now));
            return rows.isEmpty() ? null : rows.getFirst();
        });
    }

    public boolean finish(UUID id, String state, String result, Instant now) {
        if (!List.of("COMPLETE", "FAILED").contains(state)) throw new IllegalArgumentException("Invalid calibration terminal state.");
        String safe = result == null ? "" : result.substring(0, Math.min(result.length(), 3000));
        return jdbc.update("update prompt_calibration_runs set status=?,result_summary=?,completed_at=? where run_id=? and status='DISPATCHED'",
                state, safe, Timestamp.from(now), id) == 1;
    }

    public List<PromptCalibrationRun> staleDispatched(Instant cutoff) {
        return jdbc.query("""
                select run_id,prompt_revision,actor_user_id,chat_id,created_at from prompt_calibration_runs
                 where status='DISPATCHED' and started_at<? order by started_at limit 20
                """, (rs, row) -> new PromptCalibrationRun(rs.getObject("run_id", UUID.class),
                rs.getInt("prompt_revision"), rs.getLong("actor_user_id"), rs.getLong("chat_id"),
                rs.getTimestamp("created_at").toInstant()), Timestamp.from(cutoff));
    }

    public boolean finishUnknown(PromptCalibrationRun run, String result, Instant now, Instant cutoff) {
        String safe = result == null ? "" : result.substring(0, Math.min(result.length(), 3000));
        return jdbc.update("""
                update prompt_calibration_runs set status='UNKNOWN',result_summary=?,completed_at=?
                 where run_id=? and status='DISPATCHED' and started_at<?
                """, safe, Timestamp.from(now), run.id(), Timestamp.from(cutoff)) == 1;
    }

    public int count(String state) {
        return jdbc.queryForObject("select count(*) from prompt_calibration_runs where status=?", Integer.class, state);
    }
}
