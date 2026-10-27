package dev.hugo.watcher;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Maps only the stable message-upsert envelope currently confirmed by Evolution's Baileys handler. */
public final class EvolutionEventMapper {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public List<EvolutionEvent> map(JsonNode envelope, String distribution) {
        if (envelope == null || !envelope.isObject()
                || !"messages.upsert".equals(text(envelope.get("event")))) {
            return List.of();
        }

        JsonNode data = envelope.get("data");
        if (data == null || data.isNull()) return List.of();

        List<JsonNode> entries = new ArrayList<>();
        if (data.isArray()) data.forEach(entries::add);
        else if (data.isObject()) entries.add(data);

        String instance = text(envelope.get("instance"));
        if (instance == null || instance.isBlank()) return List.of();

        List<EvolutionEvent> events = new ArrayList<>();
        for (JsonNode entry : entries) {
            EvolutionEvent mapped = mapMessage(envelope, entry, distribution, instance);
            if (mapped != null) events.add(mapped);
        }
        return List.copyOf(events);
    }

    private EvolutionEvent mapMessage(JsonNode envelope, JsonNode data, String distribution, String instance) {
        JsonNode key = data.get("key");
        if (key == null || !key.isObject()) return null;

        String id = text(key.get("id"));
        if (id == null || id.isBlank()) return null;

        String remoteJid = text(key.get("remoteJid"));
        String participant = text(key.get("participant"));
        boolean group = remoteJid != null && remoteJid.endsWith("@g.us");
        Optional<String> sender = group
                ? nonBlank(participant)
                : nonBlank(remoteJid);
        String alternateJid = text(key.get(group ? "participantAlt" : "remoteJidAlt"));
        Set<String> aliases = sender.isPresent() && alternateJid != null && !alternateJid.isBlank()
                && !sender.get().equals(alternateJid) ? Set.of(alternateJid) : Set.of();

        Optional<Instant> timestamp = Optional.empty();
        JsonNode messageTimestamp = data.get("messageTimestamp");
        if (messageTimestamp != null && messageTimestamp.isNumber()
                && messageTimestamp.asString().matches("-?\\d+")) {
            try {
                timestamp = Optional.of(Instant.ofEpochSecond(Long.parseLong(messageTimestamp.asString())));
            } catch (RuntimeException ignored) {
                // An invalid or out-of-range upstream timestamp remains unknown.
            }
        }

        Optional<Boolean> archived = Optional.empty();
        JsonNode archivedNode = data.get("archived");
        if (archivedNode != null && archivedNode.isBoolean()) archived = Optional.of(archivedNode.booleanValue());

        Map<String, Object> payload;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = JSON.readValue(envelope.toString(), Map.class);
            payload = converted;
        } catch (JacksonException ex) {
            return null;
        }

        return new EvolutionEvent("evolution", distribution == null || distribution.isBlank() ? "unknown" : distribution,
                instance, "messages.upsert", id, timestamp, nonBlank(remoteJid), sender,
                aliases, aliases.isEmpty() ? Optional.empty()
                        : Optional.of(IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR), archived, payload);
    }

    private static String text(JsonNode node) {
        return node == null ? null : node.stringValueOpt().orElse(null);
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
