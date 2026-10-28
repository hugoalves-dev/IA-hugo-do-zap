package dev.hugo.watcher.simulation;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Objective message context only. Model evidence and message prose never establish a reference. */
public final class PersonalReferenceEvidence {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private PersonalReferenceEvidence() { }
    public static String findHash(JdbcTemplate jdbc, UUID batchId, String ownerJid, Instant now) {
        if (ownerJid == null || ownerJid.isBlank()) return null;
        var sources = jdbc.query("""
                select m.external_id,m.chat_jid,m.payload::text from simulation_batch_messages m
                join simulation_batches b on b.batch_id=m.batch_id and b.chat_jid=m.chat_jid
                where m.batch_id=? and b.expires_at>?
                order by m.received_at,m.external_id
                """, (rs,row) -> new Source(rs.getString(1),rs.getString(2),rs.getString(3)),batchId,Timestamp.from(now));
        for (var source : sources) {
            try {
                var payload = JSON.readTree(source.payload());
                if (!payload.path("fromMe").isBoolean() || payload.path("fromMe").booleanValue()
                        || payload.path("history").asBoolean(false)) continue;
                for (String variant : List.of("extendedTextMessage","imageMessage","videoMessage","documentMessage")) {
                    var context = payload.path("content").path(variant).path("contextInfo");
                    var mentioned = context.path("mentionedJid");
                    if (mentioned.isArray()) for (var jid : mentioned)
                        if (jid.isString() && ownerJid.equals(jid.stringValue())) return hash(source.id());
                    String stanza = context.path("stanzaId").stringValueOpt().orElse("");
                    if (!stanza.isBlank() && Boolean.TRUE.equals(jdbc.queryForObject("""
                            select exists(select 1 from whatsapp_owner_message_references
                            where chat_jid=? and external_id=? and expires_at>?)
                            """,Boolean.class,source.chat(),stanza,Timestamp.from(now)))) return hash(source.id());
                }
            } catch (RuntimeException invalidSource) { /* Invalid context never constitutes proof. */ }
        }
        return null;
    }
    public static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private record Source(String id,String chat,String payload) { }
}
