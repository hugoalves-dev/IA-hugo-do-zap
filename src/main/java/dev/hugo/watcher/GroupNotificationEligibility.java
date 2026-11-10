package dev.hugo.watcher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Structural, deterministic gate for conventional group notifications. */
public final class GroupNotificationEligibility {
    private static final List<String> MESSAGE_VARIANTS = List.of("extendedTextMessage", "imageMessage",
            "videoMessage", "documentMessage", "audioMessage");
    private static final List<String> WRAPPERS = List.of("ephemeralMessage", "viewOnceMessage",
            "viewOnceMessageV2", "viewOnceMessageV2Extension", "documentWithCaptionMessage");

    private GroupNotificationEligibility() { }

    public static boolean isTriggered(boolean ownerMention, boolean verifiedOwnerReply, boolean explicitGroupTrigger) {
        return ownerMention || verifiedOwnerReply || explicitGroupTrigger;
    }

    public static boolean isFresh(Instant activityAt, Instant now, Duration maximumAge) {
        return activityAt != null && now != null && maximumAge != null && !maximumAge.isNegative()
                && !maximumAge.isZero() && !activityAt.isAfter(now) && activityAt.isAfter(now.minus(maximumAge));
    }

    public static boolean hasOwnerMention(Map<String, Object> content, String ownerJid) {
        return hasOwnerMention(content, ownerJid, jid -> jid);
    }

    public static boolean hasOwnerMention(Map<String, Object> content, String ownerJid,
                                          java.util.function.Function<String, String> canonicalize) {
        if (content == null || ownerJid == null || ownerJid.isBlank()) return false;
        String canonicalOwner = canonicalize.apply(ownerJid);
        for (String variant : MESSAGE_VARIANTS) {
            Object value = content.get(variant);
            if (!(value instanceof Map<?, ?> message) || !(message.get("contextInfo") instanceof Map<?, ?> context)) continue;
            if (context.get("mentionedJid") instanceof List<?> mentioned
                    && mentioned.stream().filter(String.class::isInstance).map(String.class::cast)
                    .map(canonicalize).anyMatch(canonicalOwner::equals)) return true;
        }
        for (String wrapper : WRAPPERS) {
            if (content.get(wrapper) instanceof Map<?, ?> value && value.get("message") instanceof Map<?, ?> nested
                    && hasOwnerMention(stringMap(nested), ownerJid, canonicalize)) return true;
        }
        return false;
    }

    public static String quotedMessageId(Map<String, Object> content) {
        if (content == null) return null;
        for (String variant : MESSAGE_VARIANTS) {
            Object value = content.get(variant);
            if (value instanceof Map<?, ?> message && message.get("contextInfo") instanceof Map<?, ?> context
                    && context.get("stanzaId") instanceof String stanza && !stanza.isBlank()) return stanza;
        }
        for (String wrapper : WRAPPERS) {
            if (content.get(wrapper) instanceof Map<?, ?> value && value.get("message") instanceof Map<?, ?> nested) {
                String stanza = quotedMessageId(stringMap(nested));
                if (stanza != null) return stanza;
            }
        }
        return null;
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        var result = new java.util.LinkedHashMap<String, Object>();
        source.forEach((key, value) -> { if (key instanceof String name) result.put(name, value); });
        return result;
    }
}
