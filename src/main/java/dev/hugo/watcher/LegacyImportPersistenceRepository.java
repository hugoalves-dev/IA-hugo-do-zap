package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** PostgreSQL-native import reconciliation; the caller owns dry-run and rollback transactions. */
@Repository
public class LegacyImportPersistenceRepository {
    private final JdbcTemplate jdbc;

    public LegacyImportPersistenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> canonicalValues(String alias) {
        return jdbc.query("select canonical_identity from identity_aliases where alias=?",
                (rs, row) -> rs.getString(1), alias);
    }

    public List<IdentityAnchors> contactAnchors(String canonical) {
        return jdbc.query("select lid_jid,phone_jid from contact_identity where canonical_jid=?",
                (rs, row) -> new IdentityAnchors(rs.getString("lid_jid"), rs.getString("phone_jid")), canonical);
    }

    public List<String> otherAnchorOwners(String canonical, String lid, String phone) {
        return jdbc.query("""
                select canonical_jid from contact_identity
                where canonical_jid<>? and ((? is not null and lid_jid=?) or (? is not null and phone_jid=?))
                order by canonical_jid
                """, (rs, row) -> rs.getString(1), canonical, lid, lid, phone, phone);
    }

    public boolean contactExists(String canonical) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from contact_identity where canonical_jid=?)", Boolean.class, canonical));
    }

    public void upsertContact(String canonical, String lid, String phone, String username,
                              String displayName, String nameSource, Instant updatedAt) {
        jdbc.update("""
                insert into contact_identity(canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at)
                values (?,?,?,?,?,?,?)
                on conflict (canonical_jid) do update set
                    lid_jid=coalesce(contact_identity.lid_jid,excluded.lid_jid),
                    phone_jid=coalesce(contact_identity.phone_jid,excluded.phone_jid),
                    username=coalesce(contact_identity.username,excluded.username),
                    display_name=coalesce(contact_identity.display_name,excluded.display_name),
                    display_name_source=coalesce(contact_identity.display_name_source,excluded.display_name_source),
                    updated_at=greatest(contact_identity.updated_at,excluded.updated_at)
                """, canonical, lid, phone, username, displayName, nameSource, Timestamp.from(updatedAt));
    }

    public void upsertAlias(String alias, String canonical) {
        jdbc.update("""
                insert into identity_aliases(alias,canonical_identity) values (?,?)
                on conflict (alias) do update set canonical_identity=identity_aliases.canonical_identity
                where identity_aliases.canonical_identity=excluded.canonical_identity
                """, alias, canonical);
    }

    public List<ExistingPending> existingPending(String jid) {
        return jdbc.query("""
                select payload::text as payload,due_at,updated_at,expires_at
                from pending_notification where chat_jid=?
                """, (rs, row) -> new ExistingPending(rs.getString("payload"), rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("expires_at").toInstant()), jid);
    }

    public void insertPending(String jid, String payload, Instant dueAt, Instant updatedAt, Instant expiresAt) {
        jdbc.update("""
                insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at)
                values (?,?::jsonb,?,?,?)
                on conflict (chat_jid) do nothing
                """, jid, payload, Timestamp.from(dueAt), Timestamp.from(updatedAt), Timestamp.from(expiresAt));
    }

    public int matchingPendingCount(String jid, String payload, Instant dueAt, Instant updatedAt, Instant expiresAt) {
        Integer count = jdbc.queryForObject("""
                select count(*) from pending_notification
                 where chat_jid=? and payload=?::jsonb and due_at=? and updated_at=? and expires_at=?
                """, Integer.class, jid, payload, Timestamp.from(dueAt), Timestamp.from(updatedAt),
                Timestamp.from(expiresAt));
        return count == null ? 0 : count;
    }

    public record ExistingPending(String payload, Instant dueAt, Instant updatedAt, Instant expiresAt) { }
    public record IdentityAnchors(String lidJid, String phoneJid) { }
}
