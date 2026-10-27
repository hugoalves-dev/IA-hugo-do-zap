package dev.hugo.watcher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Normalized message event; alternateJid is usable only when the mapper has explicit pair evidence. */
public record IncomingMessage(Optional<String> externalId, String chatJid,
                              Optional<String> participantJid, Optional<String> alternateChatJid,
                              Optional<String> alternateParticipantJid,
                              boolean fromMe, String upsertType, boolean history,
                              Map<String, Object> content, Instant receivedAt) {
    public IncomingMessage {
        externalId = externalId == null ? Optional.empty() : externalId;
        participantJid = participantJid == null ? Optional.empty() : participantJid;
        alternateChatJid = alternateChatJid == null ? Optional.empty() : alternateChatJid;
        alternateParticipantJid = alternateParticipantJid == null ? Optional.empty() : alternateParticipantJid;
        content = content == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(content));
    }
}
