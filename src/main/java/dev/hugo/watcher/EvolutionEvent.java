package dev.hugo.watcher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;

public record EvolutionEvent(String source,
                             String distribution,
                             String instance,
                             String eventType,
                             String externalId,
                             Optional<Instant> timestamp,
                             Optional<String> jid,
                             Optional<String> senderJid,
                             Set<String> aliases,
                             Optional<IdentityEvidence> aliasEvidence,
                             Optional<Boolean> archived,
                             Map<String, Object> payload) {
    public EvolutionEvent {
        timestamp = timestamp == null ? Optional.empty() : timestamp;
        jid = jid == null ? Optional.empty() : jid;
        senderJid = senderJid == null ? Optional.empty() : senderJid;
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
        aliasEvidence = aliasEvidence == null ? Optional.empty() : aliasEvidence;
        archived = archived == null ? Optional.empty() : archived;
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Compatibility constructor for the durable inbox contract established in task 2. */
    public EvolutionEvent(String source, String instance, String eventType, String externalId,
                          Map<String, Object> payload) {
        this(source, "webhook", instance, eventType, externalId, Optional.empty(), Optional.empty(),
                Optional.empty(), Set.of(), Optional.empty(), Optional.empty(), payload);
    }
}
