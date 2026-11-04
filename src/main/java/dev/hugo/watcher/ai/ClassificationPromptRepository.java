package dev.hugo.watcher.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable prompt state. Only an atomic transition can change the active revision. */
@Repository
public class ClassificationPromptRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ClassificationPromptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
    }

    public record Revision(long id, String body, String contentHash, String origin, String description,
                           long authorId, Instant createdAt) {}
    public record Draft(UUID id, long ownerId, Long baseRevision, String body, String origin, String description,
                        String status, int refinementCount, long version, Instant expiresAt) {}
    public record ActiveState(Long revisionId) {}
    public record Transition(Long previousRevision, Long nextRevision, String event, long authorId, Instant changedAt) {}

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
                select * from classification_prompt_drafts where id=? and owner_id=? and expires_at>?
                """, (rs, row) -> new Draft(rs.getObject("id", UUID.class), rs.getLong("owner_id"),
                rs.getObject("base_revision", Long.class), rs.getString("body"), rs.getString("origin"),
                rs.getString("description"), rs.getString("status"), rs.getInt("refinement_count"),
                rs.getLong("version"), rs.getTimestamp("expires_at").toInstant()),
                id, ownerId, Timestamp.from(now)).stream().findFirst();
    }

    public boolean appendDraftPart(UUID id, long ownerId, long expectedVersion, String part, Instant now) {
        return jdbc.update("""
                update classification_prompt_drafts
                set body=case when body='' then ? else body || E'\\n' || ? end,
                    status='editing',version=version+1
                where id=? and owner_id=? and version=? and expires_at>? and status in ('editing','preview')
                """, part, part, id, ownerId, expectedVersion, Timestamp.from(now)) == 1;
    }

    public boolean saveProposal(UUID id, long ownerId, long expectedVersion, String body, String origin,
                                String description, Instant now) {
        return jdbc.update("""
                update classification_prompt_drafts
                set body=?,origin=?,description=?,status='preview',refinement_count=refinement_count+1,version=version+1
                where id=? and owner_id=? and version=? and expires_at>? and status in ('editing','preview')
                  and refinement_count<5
                """, body, origin, description, id, ownerId, expectedVersion, Timestamp.from(now)) == 1;
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
                    insert into classification_prompt_revisions(body,content_hash,origin,description,author_id,created_at)
                    values (?,?,?,?,?,?) returning *
                    """, (rs, row) -> readRevision(rs), revision.body(), revision.contentHash(), revision.origin(),
                    revision.description(), revision.authorId(), Timestamp.from(revision.createdAt()));
        }
        return jdbc.queryForObject("""
                insert into classification_prompt_revisions(id,body,content_hash,origin,description,author_id,created_at)
                values (?,?,?,?,?,?,?) returning *
                """, (rs, row) -> readRevision(rs), revision.id(), revision.body(), revision.contentHash(), revision.origin(),
                revision.description(), revision.authorId(), Timestamp.from(revision.createdAt()));
    }

    public Optional<Revision> revision(long id) {
        return jdbc.query("select * from classification_prompt_revisions where id=?",
                (rs, row) -> readRevision(rs), id).stream().findFirst();
    }

    public ActiveState activeState() {
        return jdbc.queryForObject("select revision_id from classification_prompt_active where singleton_id=1",
                (rs, row) -> new ActiveState(rs.getObject("revision_id", Long.class)));
    }

    public List<Transition> history(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("history limit must be between 1 and 100");
        return jdbc.query("select * from classification_prompt_transitions order by id desc limit ?",
                (rs, row) -> new Transition(rs.getObject("previous_revision", Long.class),
                        rs.getObject("next_revision", Long.class), rs.getString("event"), rs.getLong("author_id"),
                        rs.getTimestamp("changed_at").toInstant()), limit);
    }

    public boolean transition(Long expectedActiveRevision, Long nextRevision, String event, long authorId, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int updated = jdbc.update("""
                    update classification_prompt_active set revision_id=?
                    where singleton_id=1 and revision_id is not distinct from ?
                    """, nextRevision, expectedActiveRevision);
            if (updated == 0) return false;
            jdbc.update("""
                    insert into classification_prompt_transitions(previous_revision,next_revision,event,author_id,changed_at)
                    values (?,?,?,?,?)
                    """, expectedActiveRevision, nextRevision, event, authorId, Timestamp.from(now));
            return true;
        }));
    }

    public static String contentHash(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Revision readRevision(ResultSet rs) throws SQLException {
        return new Revision(rs.getLong("id"), rs.getString("body"), rs.getString("content_hash"), rs.getString("origin"),
                rs.getString("description"), rs.getLong("author_id"), rs.getTimestamp("created_at").toInstant());
    }
}
