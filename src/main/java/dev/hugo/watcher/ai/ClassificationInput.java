package dev.hugo.watcher.ai;

import dev.hugo.watcher.IncomingMessage;
import dev.hugo.watcher.simulation.Batch;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Bounded and deterministically minimized text passed to a classification provider. */
public final class ClassificationInput {
    public static final int MAX_MESSAGES = 6;
    public static final int MAX_CHARS_PER_MESSAGE = 1_000;
    public record ContextEntry(String role, String text, String senderName, String senderId) {
        public ContextEntry(String role, String text) { this(role, text, null, null); }
    }
    public record SenderIdentity(String canonicalId, String displayName) { }
    private final List<ContextEntry> entries;
    private final java.util.UUID batchId;

    public ClassificationInput(List<String> messages) {
        this(messages == null ? List.of() : messages.stream().filter(java.util.Objects::nonNull)
                .map(text -> new ContextEntry("contact", text)).toList(), null);
    }
    private ClassificationInput(List<ContextEntry> source, java.util.UUID batchId) {
        var sanitized = source.stream().filter(e -> e.text() != null && !e.text().isBlank())
                .map(e -> new ContextEntry(e.role(), sanitize(e.text()), safeDisplayName(e.senderName()), safeSenderId(e.senderId()))).toList();
        entries = List.copyOf(sanitized.subList(Math.max(0, sanitized.size() - MAX_MESSAGES), sanitized.size()));
        this.batchId = batchId;
    }
    private static String sanitize(String value) {
        String text = ClassificationInputMinimizer.minimize(value.strip());
        return text.length() > MAX_CHARS_PER_MESSAGE ? text.substring(0, MAX_CHARS_PER_MESSAGE) : text;
    }
    public List<String> messages() { return entries.stream().map(ContextEntry::text).toList(); }
    public String target() { return entries.isEmpty() ? "" : entries.getLast().text(); }
    public ContextEntry targetEntry() { return entries.isEmpty() ? new ContextEntry("contact", "") : entries.getLast(); }
    public List<String> context() { return contextEntries().stream().map(ContextEntry::text).toList(); }
    public List<ContextEntry> contextEntries() { return entries.isEmpty() ? List.of() : entries.subList(0, entries.size() - 1); }
    public java.util.UUID batchId() { return batchId; }

    /** Synthetic prompt-calibration input contains no WhatsApp identity or persisted conversation text. */
    public static ClassificationInput sanitizedFixture(List<ContextEntry> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_MESSAGES
                || entries.stream().anyMatch(entry -> entry == null || !List.of("owner", "contact").contains(entry.role())))
            throw new IllegalArgumentException("Invalid synthetic classification fixture.");
        return new ClassificationInput(entries, null);
    }

    public static ClassificationInput fromBatch(Batch batch) {
        return fromBatch(batch, false, null);
    }

    public static ClassificationInput fromBatch(Batch batch, Function<String, SenderIdentity> senderResolver) {
        return fromBatch(batch, false, senderResolver);
    }

    static ClassificationInput fromContext(Batch batch) {
        return fromBatch(batch, true, null);
    }

    static ClassificationInput fromContext(Batch batch, Function<String, SenderIdentity> senderResolver) {
        return fromBatch(batch, true, senderResolver);
    }

    private static ClassificationInput fromBatch(Batch batch, boolean verifiedGroupOwnerContext,
                                                  Function<String, SenderIdentity> senderResolver) {
        if (batch == null || batch.messages() == null) return new ClassificationInput(List.of());
        var ordered = batch.messages().stream().filter(java.util.Objects::nonNull)
                .filter(message -> !message.history())
                .sorted(java.util.Comparator.comparing(IncomingMessage::receivedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(message -> message.externalId().orElse("")))
                .toList();
        int targetIndex = -1;
        for (int i = 0; i < ordered.size(); i++) {
            IncomingMessage message = ordered.get(i);
            if (!message.fromMe() && !extractText(message.content()).isBlank()) targetIndex = i;
        }
        if (targetIndex < 0) return new ClassificationInput(List.of());
        List<ContextEntry> text = new ArrayList<>();
        Map<String, String> localSenderIds = new LinkedHashMap<>();
        for (int i = 0; i <= targetIndex; i++) {
            IncomingMessage message = ordered.get(i);
            if (message.fromMe() && batch.chatJid().endsWith("@g.us") && !verifiedGroupOwnerContext) continue;
            String extracted = extractText(message.content());
            if (extracted.isBlank()) continue;
            String role = message.fromMe() ? "owner" : batch.chatJid().endsWith("@g.us") ? "group_participant" : "contact";
            String senderName = null;
            String senderId = null;
            if (senderResolver != null) {
                if (message.fromMe()) {
                    senderName = "Proprietário";
                    senderId = "owner";
                } else {
                    String jid = "group_participant".equals(role)
                            ? message.participantJid().orElse(null) : message.chatJid();
                    SenderIdentity identity = jid == null ? null : senderResolver.apply(jid);
                    String canonical = identity == null || identity.canonicalId() == null ? jid : identity.canonicalId();
                    if (canonical != null) senderId = localSenderIds.computeIfAbsent(canonical,
                            ignored -> "sender_" + (localSenderIds.size() + 1));
                    senderName = safeDisplayName(identity == null ? null : identity.displayName());
                    if (senderName == null) senderName = "contact".equals(role) ? "Contato" : "Participante do grupo";
                }
            }
            text.add(new ContextEntry(role, extracted, senderName, senderId));
        }
        return new ClassificationInput(text, batch.id());
    }

    private static String safeDisplayName(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.strip().replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ");
        if (cleaned.isBlank() || cleaned.length() > 100 || cleaned.matches("(?i)^\\+?[0-9 .()/-]{7,}$")) return null;
        return cleaned;
    }

    private static String safeSenderId(String value) {
        return value != null && value.matches("(?:owner|sender_[1-9][0-9]*)") ? value : null;
    }

    public static String extractText(Map<String, Object> content) {
        Map<?, ?> current = content;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Object conversation = current.get("conversation");
            if (conversation instanceof String text) return text;
            Object extended = current.get("extendedTextMessage");
            if (extended instanceof Map<?, ?> map && map.get("text") instanceof String text) return text;
            for (String type : List.of("imageMessage", "videoMessage", "documentMessage")) {
                Object media = current.get(type);
                if (media instanceof Map<?, ?> map && map.get("caption") instanceof String caption) return caption;
            }
            Object nested = null;
            for (String wrapper : List.of("ephemeralMessage", "viewOnceMessage", "viewOnceMessageV2",
                    "viewOnceMessageV2Extension", "documentWithCaptionMessage")) {
                if (current.get(wrapper) instanceof Map<?, ?> map) {
                    nested = map.get("message");
                    break;
                }
            }
            current = nested instanceof Map<?, ?> map ? map : null;
        }
        return "";
    }
}
