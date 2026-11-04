package dev.hugo.watcher.ai;

import dev.hugo.watcher.telegram.RuntimeSettingVersionConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcClassificationPromptRepository implements ClassificationPromptRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcClassificationPromptRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override public Optional<PromptRevision> active() {
        List<PromptRevision> rows = jdbc.query("""
                select r.revision,r.prompt_body,r.sha256,r.author_user_id,r.created_at
                  from classification_prompt_state s join classification_prompt_revisions r
                    on r.revision=s.active_revision
                 where s.singleton_id=1
                """, this::map);
        return rows.stream().findFirst();
    }

    @Override public Optional<PromptRevision> find(int revision) {
        return jdbc.query("select revision,prompt_body,sha256,author_user_id,created_at from classification_prompt_revisions where revision=?",
                this::map, revision).stream().findFirst();
    }

    @Override public List<PromptRevision> latest(int limit) {
        int bounded = Math.max(1, Math.min(20, limit));
        return jdbc.query("select revision,prompt_body,sha256,author_user_id,created_at from classification_prompt_revisions order by revision desc limit ?",
                this::map, bounded);
    }

    @Override public PromptRevision createDraft(String body, String hash, long actor, Instant at) {
        return jdbc.queryForObject("""
                insert into classification_prompt_revisions(prompt_body,sha256,author_user_id,created_at)
                values (?,?,?,?) returning revision,prompt_body,sha256,author_user_id,created_at
                """, this::map, body, hash, actor, Timestamp.from(at));
    }

    @Override public long activate(int revision, long expectedVersion, long actor, Instant at) {
        return transactions.execute(status -> {
            if (find(revision).isEmpty()) throw new IllegalArgumentException("Prompt revision not found.");
            Integer previous = jdbc.query("select active_revision from classification_prompt_state where singleton_id=1",
                    rs -> rs.next() ? (Integer) rs.getObject(1) : null);
            int changed = jdbc.update("""
                    update classification_prompt_state set active_revision=?,version=version+1,updated_by=?,updated_at=?
                     where singleton_id=1 and version=?
                    """, revision, actor, Timestamp.from(at), expectedVersion);
            if (changed != 1) throw new RuntimeSettingVersionConflictException();
            long newVersion = expectedVersion + 1;
            jdbc.update("insert into classification_prompt_state_history(version,previous_revision,next_revision,actor_user_id,changed_at) values (?,?,?,?,?)",
                    newVersion, previous, revision, actor, Timestamp.from(at));
            return newVersion;
        });
    }

    @Override public long activeVersion() {
        Long version = jdbc.queryForObject("select version from classification_prompt_state where singleton_id=1", Long.class);
        return version == null ? 0 : version;
    }

    @Override public boolean hasPendingInput(long userId, long chatId, Instant now) {
        Boolean pending = jdbc.queryForObject("""
                select exists(select 1 from telegram_prompt_input_sessions
                    where user_id=? and chat_id=? and consumed_at is null and expires_at>?)
                """, Boolean.class, userId, chatId, Timestamp.from(now));
        return Boolean.TRUE.equals(pending);
    }

    @Override public void beginInput(long userId, long chatId, Instant expiresAt, Instant now) {
        jdbc.update("""
                insert into telegram_prompt_input_sessions(user_id,chat_id,expires_at,created_at,consumed_at)
                values (?,?,?,?,null)
                on conflict(user_id) do update set chat_id=excluded.chat_id,expires_at=excluded.expires_at,
                    created_at=excluded.created_at,consumed_at=null
                """, userId, chatId, Timestamp.from(expiresAt), Timestamp.from(now));
    }

    @Override public PromptRevision createDraftFromInput(long userId, long chatId, String body, String hash, Instant at) {
        return transactions.execute(status -> {
            List<Long> consumed = jdbc.query("""
                    update telegram_prompt_input_sessions set consumed_at=?
                     where user_id=? and chat_id=? and consumed_at is null and expires_at>?
                    returning user_id
                    """, (rs, row) -> rs.getLong(1), Timestamp.from(at), userId, chatId, Timestamp.from(at));
            if (consumed.isEmpty()) throw new IllegalArgumentException("Prompt input session is absent or expired.");
            return createDraft(body, hash, userId, at);
        });
    }

    private PromptRevision map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new PromptRevision(rs.getInt("revision"), rs.getString("prompt_body"), rs.getString("sha256"),
                rs.getLong("author_user_id"), rs.getTimestamp("created_at").toInstant());
    }
}
