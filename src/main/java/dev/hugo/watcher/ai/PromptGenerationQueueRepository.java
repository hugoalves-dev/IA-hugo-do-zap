package dev.hugo.watcher.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class PromptGenerationQueueRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate independentTransactions;

    public PromptGenerationQueueRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.independentTransactions = new TransactionTemplate(manager);
        this.independentTransactions.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public UUID enqueue(UUID draftId, long version, long actor, long chat, String operation, String instructions, Instant now) {
        if (draftId == null || version < 0 || actor <= 0 || chat <= 0 || instructions == null
                || instructions.isBlank() || instructions.length() > 4096
                || !List.of("GENERATE", "REFINE").contains(operation)) throw new IllegalArgumentException("invalid_prompt_generation_job");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into prompt_generation_jobs(job_id,draft_id,expected_draft_version,actor_user_id,chat_id,operation,instructions,status,created_at) values (?,?,?,?,?,?,?,'PENDING',?)",
                id, draftId, version, actor, chat, operation, instructions, Timestamp.from(now));
        return id;
    }

    /** The transaction commits the DISPATCHED marker before the worker can call OpenRouter. */
    public PromptGenerationJob claimPending(Instant now) {
        return transactions.execute(status -> {
            var rows = jdbc.query("""
                    with candidate as (select job_id from prompt_generation_jobs where status='PENDING'
                     order by created_at,job_id limit 1 for update skip locked)
                    update prompt_generation_jobs j set status='DISPATCHED',started_at=? from candidate c
                     where j.job_id=c.job_id returning j.job_id,j.draft_id,j.expected_draft_version,j.actor_user_id,
                     j.chat_id,j.operation,j.instructions,j.created_at,j.failure_stage,j.failure_code
                    """, (rs, row) -> new PromptGenerationJob(rs.getObject("job_id", UUID.class),
                    rs.getObject("draft_id", UUID.class), rs.getLong("expected_draft_version"),
                    rs.getLong("actor_user_id"), rs.getLong("chat_id"), rs.getString("operation"),
                    rs.getString("instructions"), rs.getTimestamp("created_at").toInstant(), diagnostic(rs)), Timestamp.from(now));
            return rows.isEmpty() ? null : rows.getFirst();
        });
    }

    public boolean finish(UUID id, String state, Instant now) {
        return finish(id, state, now, null);
    }

    public boolean finish(UUID id, String state, Instant now, PromptGenerationFailure failure) {
        if (!List.of("COMPLETE", "FAILED").contains(state)) throw new IllegalArgumentException("invalid_prompt_generation_state");
        if ("COMPLETE".equals(state) && failure != null) throw new IllegalArgumentException("successful prompt job cannot carry a failure");
        PromptGenerationFailure terminalFailure = "FAILED".equals(state)
                ? java.util.Objects.requireNonNullElseGet(failure, () -> new PromptGenerationFailure(
                        PromptGenerationFailure.Stage.PROVIDER, PromptGenerationFailure.ReasonCode.PROVIDER_FAILURE))
                : null;
        return jdbc.update("""
                update prompt_generation_jobs set status=?,instructions='',completed_at=?,failure_stage=?,failure_code=?
                where job_id=? and status='DISPATCHED'
                """, state, Timestamp.from(now), terminalFailure == null ? null : terminalFailure.stage().name(),
                terminalFailure == null ? null : terminalFailure.reasonCode().name(), id) == 1;
    }

    public List<PromptGenerationJob> staleDispatched(Instant cutoff) {
        return jdbc.query("""
                select job_id,draft_id,expected_draft_version,actor_user_id,chat_id,operation,instructions,created_at,
                       failure_stage,failure_code
                from prompt_generation_jobs where status='DISPATCHED' and started_at<? order by started_at limit 20
                """,
                (rs, row) -> new PromptGenerationJob(rs.getObject("job_id", UUID.class), rs.getObject("draft_id", UUID.class),
                        rs.getLong("expected_draft_version"), rs.getLong("actor_user_id"), rs.getLong("chat_id"),
                        rs.getString("operation"), rs.getString("instructions"), rs.getTimestamp("created_at").toInstant(),
                        diagnostic(rs)), Timestamp.from(cutoff));
    }

    public boolean finishUnknown(PromptGenerationJob job, Instant now, Instant cutoff) {
        var diagnostic = job.diagnostic() == null ? new PromptGenerationFailure(
                PromptGenerationFailure.Stage.CLAIM, PromptGenerationFailure.ReasonCode.WORKER_INTERRUPTED) : job.diagnostic();
        return jdbc.update("""
                update prompt_generation_jobs set status='UNKNOWN',instructions='',completed_at=?,
                    failure_stage=coalesce(failure_stage,?),failure_code=coalesce(failure_code,?)
                where job_id=? and status='DISPATCHED' and started_at<?
                """, Timestamp.from(now), diagnostic.stage().name(), diagnostic.reasonCode().name(),
                job.id(), Timestamp.from(cutoff)) == 1;
    }

    /** Persists the safe reason after terminal-state/outbox admission rolls back. */
    public boolean recordResultEnqueueFailure(UUID id) {
        return Boolean.TRUE.equals(independentTransactions.execute(status -> jdbc.update("""
                update prompt_generation_jobs set failure_stage='OUTBOX',failure_code='RESULT_ENQUEUE_FAILED',
                    result_delivery_last_error='RESULT_ENQUEUE_FAILED'
                where job_id=? and status='DISPATCHED'
                """, id) == 1));
    }

    private static PromptGenerationFailure diagnostic(ResultSet rs) throws SQLException {
        String stage = rs.getString("failure_stage");
        String reason = rs.getString("failure_code");
        if (stage == null || reason == null) return null;
        return new PromptGenerationFailure(PromptGenerationFailure.Stage.valueOf(stage),
                PromptGenerationFailure.ReasonCode.valueOf(reason));
    }
}
