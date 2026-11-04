package dev.hugo.watcher.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class PromptGenerationQueueRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PromptGenerationQueueRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
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
                     j.chat_id,j.operation,j.instructions,j.created_at
                    """, (rs, row) -> new PromptGenerationJob(rs.getObject("job_id", UUID.class),
                    rs.getObject("draft_id", UUID.class), rs.getLong("expected_draft_version"),
                    rs.getLong("actor_user_id"), rs.getLong("chat_id"), rs.getString("operation"),
                    rs.getString("instructions"), rs.getTimestamp("created_at").toInstant()), Timestamp.from(now));
            return rows.isEmpty() ? null : rows.getFirst();
        });
    }

    public boolean finish(UUID id, String state, Instant now) {
        if (!List.of("COMPLETE", "FAILED").contains(state)) throw new IllegalArgumentException("invalid_prompt_generation_state");
        return jdbc.update("update prompt_generation_jobs set status=?,instructions='',completed_at=? where job_id=? and status='DISPATCHED'",
                state, Timestamp.from(now), id) == 1;
    }

    public List<PromptGenerationJob> staleDispatched(Instant cutoff) {
        return jdbc.query("select job_id,draft_id,expected_draft_version,actor_user_id,chat_id,operation,instructions,created_at from prompt_generation_jobs where status='DISPATCHED' and started_at<? order by started_at limit 20",
                (rs, row) -> new PromptGenerationJob(rs.getObject("job_id", UUID.class), rs.getObject("draft_id", UUID.class),
                        rs.getLong("expected_draft_version"), rs.getLong("actor_user_id"), rs.getLong("chat_id"),
                        rs.getString("operation"), rs.getString("instructions"), rs.getTimestamp("created_at").toInstant()), Timestamp.from(cutoff));
    }

    public boolean finishUnknown(PromptGenerationJob job, Instant now, Instant cutoff) {
        return jdbc.update("update prompt_generation_jobs set status='UNKNOWN',instructions='',completed_at=? where job_id=? and status='DISPATCHED' and started_at<?",
                Timestamp.from(now), job.id(), Timestamp.from(cutoff)) == 1;
    }
}
