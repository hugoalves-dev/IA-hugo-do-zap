package dev.hugo.watcher.simulation;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import dev.hugo.watcher.simulation.PersonalReferenceEvidenceRepository.Source;
import java.util.UUID;

/** Objective message context only. Model evidence and message prose never establish a reference. */
public final class PersonalReferenceEvidence {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private PersonalReferenceEvidence() { }
    /** Compatibility adapter for legacy callers and standalone tests. */
    public static String findHash(JdbcTemplate jdbc, UUID batchId, String ownerJid, Instant now) {
        return findHash(new PersonalReferenceEvidenceRepository(jdbc), batchId, ownerJid, now);
    }

    public static String findHash(PersonalReferenceEvidenceRepository evidence, UUID batchId, String ownerJid, Instant now) {
        if (ownerJid == null || ownerJid.isBlank()) return null;
        var sources = evidence.sources(batchId, now);
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
                    if (!stanza.isBlank() && evidence.hasOwnerMessageReference(source.chat(), stanza, now)) return hash(source.id());
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
}
