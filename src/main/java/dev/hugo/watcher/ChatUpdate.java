package dev.hugo.watcher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Archive-state observation supplied by a verified source or synchronized snapshot. */
public record ChatUpdate(String chatJid, Optional<Boolean> archived, Instant timestamp,
                         boolean synchronizedSnapshot, boolean archiveSourceVerified,
                         Optional<String> externalId, Optional<String> displayName,
                         boolean group, Map<String, Object> payload) {
    public ChatUpdate {
        archived = archived == null ? Optional.empty() : archived;
        externalId = externalId == null ? Optional.empty() : externalId;
        displayName = displayName == null ? Optional.empty() : displayName;
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
