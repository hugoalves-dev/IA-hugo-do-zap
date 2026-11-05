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
import java.util.UUID;

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
                    created_at=excluded.created_at,consumed_at=null,draft_id=null
                """, userId, chatId, Timestamp.from(expiresAt), Timestamp.from(now));
    }

    @Override public void beginDraftInput(long userId, long chatId, UUID draftId, Instant expiresAt, Instant now) {
        jdbc.update("""
                insert into telegram_prompt_input_sessions(user_id,chat_id,expires_at,created_at,consumed_at,draft_id)
                values (?,?,?,?,null,?)
                on conflict(user_id) do update set chat_id=excluded.chat_id,expires_at=excluded.expires_at,
                    created_at=excluded.created_at,consumed_at=null,draft_id=excluded.draft_id
                """, userId, chatId, Timestamp.from(expiresAt), Timestamp.from(now), draftId);
    }

    @Override public Optional<UUID> pendingDraftInput(long userId, long chatId, Instant now) {
        return jdbc.query("""
                select draft_id from telegram_prompt_input_sessions
                where user_id=? and chat_id=? and consumed_at is null and expires_at>? and draft_id is not null
                """, (rs, row) -> rs.getObject(1, UUID.class), userId, chatId, Timestamp.from(now)).stream().findFirst();
    }

    @Override public boolean endDraftInput(long userId, long chatId, UUID draftId, Instant now) {
        return jdbc.update("""
                update telegram_prompt_input_sessions set consumed_at=?
                where user_id=? and chat_id=? and draft_id=? and consumed_at is null and expires_at>?
                """, Timestamp.from(now), userId, chatId, draftId, Timestamp.from(now)) == 1;
    }

    @Override public PromptRevision createDraftFromInput(long userId, long chatId, String body, String hash, Instant at) {
        return transactions.execute(status -> {
            List<Long> consumed = jdbc.query("""
                    update telegram_prompt_input_sessions set consumed_at=?
                     where user_id=? and chat_id=? and consumed_at is null and expires_at>? and draft_id is null
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
    public void createDraft(Draft draft) {
        jdbc.update("""
                insert into classification_prompt_drafts
                    (id,owner_id,base_revision,body,origin,description,status,refinement_count,version,expires_at)
                values (?,?,?,?,?,?,?,?,?,?)
                """, draft.id(), draft.ownerId(), draft.baseRevision(), draft.body(), draft.origin(), draft.description(),
                draft.status(), draft.refinementCount(), draft.version(), Timestamp.from(draft.expiresAt()));
    }

    public Optional<Draft> draft(UUID id, long ownerId, Instant now) {
        return jdbc.query("""
                select id,owner_id,base_revision::bigint as base_revision,body,origin,description,status,refinement_count,version,expires_at from classification_prompt_drafts where id=? and owner_id=? and expires_at>?
                """, (rs, row) -> new Draft(rs.getObject("id", UUID.class), rs.getLong("owner_id"),
                rs.getObject("base_revision", Long.class), rs.getString("body"), rs.getString("origin"),
                rs.getString("description"), rs.getString("status"), rs.getInt("refinement_count"),
                rs.getLong("version"), rs.getTimestamp("expires_at").toInstant()),
                id, ownerId, Timestamp.from(now)).stream().findFirst();
    }

    /** Returns an owner's draft regardless of expiry so prompt generation can report DRAFT_EXPIRED safely. */
    @Override public Optional<Draft> draftForGeneration(UUID id, long ownerId) {
        return jdbc.query("""
                select id,owner_id,base_revision::bigint as base_revision,body,origin,description,status,
                       refinement_count,version,expires_at
                from classification_prompt_drafts where id=? and owner_id=?
                """, (rs, row) -> new Draft(rs.getObject("id", UUID.class), rs.getLong("owner_id"),
                rs.getObject("base_revision", Long.class), rs.getString("body"), rs.getString("origin"),
                rs.getString("description"), rs.getString("status"), rs.getInt("refinement_count"),
                rs.getLong("version"), rs.getTimestamp("expires_at").toInstant()), id, ownerId).stream().findFirst();
    }

    public boolean appendDraftPart(UUID id, long ownerId, long expectedVersion, String part, Instant now) {
        return jdbc.update("""
                update classification_prompt_drafts
                set body=case when body='' then ? else body || E'\\n' || ? end,
                    status='editing',version=version+1
                where id=? and owner_id=? and version=? and expires_at>? and status in ('editing','preview')
                """, part, part, id, ownerId, expectedVersion, Timestamp.from(now)) == 1;
    }

    public boolean clearDraft(UUID id, long ownerId, long expectedVersion, Instant now) {
        return jdbc.update("""
                update classification_prompt_drafts set body='',status='editing',version=version+1
                where id=? and owner_id=? and version=? and expires_at>? and status in ('editing','preview')
                """, id, ownerId, expectedVersion, Timestamp.from(now)) == 1;
    }

    public boolean saveProposal(UUID id, long ownerId, long expectedVersion, String body, String origin,
                                String description, Instant now) {
        return jdbc.update("""
                update classification_prompt_drafts
                set body=?,origin=?,description=?,status='preview',
                    refinement_count=refinement_count+case when origin='ai_assisted' and ?='ai_assisted' then 1 else 0 end,
                    version=version+1
                where id=? and owner_id=? and version=? and expires_at>? and status in ('editing','preview')
                  and (origin<>'ai_assisted' or ?<>'ai_assisted' or refinement_count<3)
                """, body, origin, description, origin, id, ownerId, expectedVersion, Timestamp.from(now), origin) == 1;
    }

    public boolean setDraftStatus(UUID id, long ownerId, long expectedVersion, String status) {
        return jdbc.update("""
                update classification_prompt_drafts set status=?,version=version+1
                where id=? and owner_id=? and version=? and status in ('editing','preview')
                """, status, id, ownerId, expectedVersion) == 1;
    }

    /** A zero ID allocates a new immutable revision identity. */
    public Revision insertRevision(Revision revision) {
        if (revision.id() == 0) {
            return jdbc.queryForObject("""
                    insert into classification_prompt_revisions(prompt_body,sha256,origin,description,author_user_id,created_at)
                    values (?,?,?,?,?,?) returning *
                    """, (rs, row) -> readRevision(rs), revision.body(), revision.contentHash(), revision.origin(),
                    revision.description(), revision.authorId(), Timestamp.from(revision.createdAt()));
        }
        return jdbc.queryForObject("""
                insert into classification_prompt_revisions(revision,prompt_body,sha256,origin,description,author_user_id,created_at) overriding system value
                values (?,?,?,?,?,?,?) returning *
                """, (rs, row) -> readRevision(rs), revision.id(), revision.body(), revision.contentHash(), revision.origin(),
                revision.description(), revision.authorId(), Timestamp.from(revision.createdAt()));
    }

    public Optional<Revision> revision(long id) {
        return jdbc.query("select * from classification_prompt_revisions where revision=?",
                (rs, row) -> readRevision(rs), id).stream().findFirst();
    }

    public ActiveState activeState() {
        return jdbc.queryForObject("select active_revision::bigint as revision_id,version from classification_prompt_state where singleton_id=1",
                (rs, row) -> new ActiveState(rs.getObject("revision_id", Long.class), rs.getLong("version")));
    }

    public ActiveState lockActiveState() {
        return jdbc.queryForObject("select active_revision::bigint as revision_id,version from classification_prompt_state where singleton_id=1 for update",
                (rs, row) -> new ActiveState(rs.getObject("revision_id", Long.class), rs.getLong("version")));
    }

    public List<Transition> history(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("history limit must be between 1 and 100");
        return jdbc.query("select previous_revision::bigint as previous_revision,next_revision::bigint as next_revision,event,actor_user_id as author_id,changed_at from classification_prompt_state_history order by version desc limit ?",
                (rs, row) -> new Transition(rs.getObject("previous_revision", Long.class),
                        rs.getObject("next_revision", Long.class), rs.getString("event"), rs.getLong("author_id"),
                        rs.getTimestamp("changed_at").toInstant()), limit);
    }

    public boolean transition(Long expectedActiveRevision, Long nextRevision, String event, long authorId, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated = jdbc.update("""
                    update classification_prompt_state set active_revision=?,version=version+1,updated_by=?,updated_at=?
                    where singleton_id=1 and active_revision is not distinct from ?
                    """, nextRevision, authorId, Timestamp.from(now), expectedActiveRevision);
            if (updated == 0) return false;
            jdbc.update("""
                    insert into classification_prompt_state_history(version,previous_revision,next_revision,event,actor_user_id,changed_at)
                    select version,?,?,?,?,? from classification_prompt_state where singleton_id=1
                    """, expectedActiveRevision, nextRevision, event, authorId, Timestamp.from(now));
            return true;
        }));
    }

    private static Revision readRevision(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Revision(rs.getLong("revision"), rs.getString("prompt_body"), rs.getString("sha256"), rs.getString("origin"), rs.getString("description"), rs.getLong("author_user_id"), rs.getTimestamp("created_at").toInstant());
    }
}
