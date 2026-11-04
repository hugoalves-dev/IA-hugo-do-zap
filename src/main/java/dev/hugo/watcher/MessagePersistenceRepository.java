package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Native PostgreSQL access for retained message snapshots, deduplication and pending
 * notifications. The caller controls transaction boundaries for ordered processing.
 * ON CONFLICT and FOR UPDATE preserve the original exactly-once safeguards.
 */
@Repository
public class MessagePersistenceRepository {
    private final JdbcTemplate jdbc;

    public MessagePersistenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String canonicalChat(String jid) {
        List<String> aliases = jdbc.query("select canonical_identity from identity_aliases where alias=?",
                (rs, row) -> rs.getString(1), jid);
        return aliases.isEmpty() ? jid : aliases.getFirst();
    }

    public List<ChatSnapshot> snapshot(String jid, boolean forUpdate) {
        String sql = """
                select archived, synchronized_snapshot, display_name, is_group, last_read_at
                  from chat_snapshot where chat_jid=?
                """ + (forUpdate ? " for update" : "");
        return jdbc.query(sql, (rs, row) -> new ChatSnapshot((Boolean) rs.getObject("archived"),
                rs.getBoolean("synchronized_snapshot"), rs.getString("display_name"),
                rs.getBoolean("is_group"), rs.getTimestamp("last_read_at") == null
                    ? null : rs.getTimestamp("last_read_at").toInstant()), jid);
    }

    public void captureOwnerContext(String chat, String externalId, Instant receivedAt, Instant capturedAt,
                                    Instant expiresAt, String text, String replyToExternalId) {
        jdbc.update("""
                insert into simulation_owner_context(chat_jid,external_id,received_at,captured_at,expires_at,text,reply_to_external_id)
                values (?,?,?,?,?,?,?) on conflict do nothing
                """, chat, externalId, Timestamp.from(receivedAt), Timestamp.from(capturedAt),
                Timestamp.from(expiresAt), text, replyToExternalId);
    }

    public void recordOutgoingReference(String chatJid, String externalId, Instant observedAt, Instant expiresAt) {
        jdbc.update("""
                insert into whatsapp_owner_message_references(chat_jid,external_id,observed_at,expires_at)
                values (?,?,?,?) on conflict (chat_jid,external_id) do nothing
                """, chatJid, externalId, Timestamp.from(observedAt), Timestamp.from(expiresAt));
    }

    public void deletePending(String chat) {
        jdbc.update("delete from pending_notification where chat_jid=?", chat);
    }

    public int insertMessageDedup(String dedupKey, Instant at, String canonicalChat, String upstreamMessageKey,
                                  String participant, String shadowPayload, Instant shadowExpiresAt) {
        return jdbc.update("""
                insert into processed_message(message_id, processed_at, canonical_chat_jid, upstream_message_key, canonical_participant_jid, shadow_payload, shadow_expires_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                on conflict do nothing
                """, dedupKey, Timestamp.from(at), canonicalChat, upstreamMessageKey, participant,
                shadowPayload, Timestamp.from(shadowExpiresAt));
    }

    public void upsertPending(String chat, String payloadJson, Instant dueAt, Instant updatedAt, Instant expiresAt) {
        jdbc.update("""
                insert into pending_notification(chat_jid, payload, due_at, updated_at, expires_at)
                values (?, ?::jsonb, ?, ?, ?)
                on conflict (chat_jid) do update set
                    payload=excluded.payload,
                    due_at=excluded.due_at,
                    updated_at=excluded.updated_at,
                    expires_at=excluded.expires_at
                """, chat, payloadJson, Timestamp.from(dueAt), Timestamp.from(updatedAt), Timestamp.from(expiresAt));
    }

    public Pending readPendingForUpdate(String jid) {
        List<Pending> rows = jdbc.query("""
                select coalesce((payload->>'messageCount')::integer, 0) as message_count,
                       payload->>'firstMessageAt' as first_message_at,
                       payload->>'lastMessageAt' as last_message_at,
                       payload->>'preview' as preview,
                       payload->>'previewSenderJid' as preview_sender_jid,
                       payload->>'previewExpiresAt' as preview_expires_at,
                       expires_at
                  from pending_notification where chat_jid=? for update
                """, (rs, row) -> new Pending(rs.getInt("message_count"),
                Instant.parse(rs.getString("first_message_at")), Instant.parse(rs.getString("last_message_at")),
                rs.getString("preview"),
                rs.getString("preview_sender_jid"),
                parseInstant(rs.getString("preview_expires_at"), rs.getTimestamp("expires_at").toInstant())), jid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void quarantine(String kind, String externalId, String reason, Instant now) {
        QuarantineWriter.write(jdbc, kind, externalId, reason, now);
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null) return fallback;
        try { return Instant.parse(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    public record ChatSnapshot(Boolean archived, boolean synchronizedSnapshot,
                               String displayName, boolean group, Instant lastReadAt) { }
    public record Pending(int count, Instant firstAt, Instant lastAt, String preview, String previewSenderJid,
                          Instant previewExpiresAt) { }
}
