package dev.hugo.watcher.simulation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only PostgreSQL evidence. Explicit message reference is never inferred from prose. */
@Repository
public class PersonalReferenceEvidenceRepository {
    private final JdbcTemplate jdbc;

    public PersonalReferenceEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Source> sources(UUID batchId, Instant now) {
        return jdbc.query("""
                select m.external_id,m.chat_jid,m.payload::text from simulation_batch_messages m
                join simulation_batches b on b.batch_id=m.batch_id and b.chat_jid=m.chat_jid
                where m.batch_id=? and b.expires_at>?
                order by m.received_at,m.external_id
                """, (rs,row) -> new Source(rs.getString(1),rs.getString(2),rs.getString(3)),batchId,Timestamp.from(now));
    }

    public boolean hasOwnerMessageReference(String chatJid, String stanzaId, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                            select exists(select 1 from whatsapp_owner_message_references
                            where chat_jid=? and external_id=? and expires_at>?)
                            """,Boolean.class,chatJid,stanzaId,Timestamp.from(now)));
    }

    public record Source(String id, String chat, String payload) { }
}
