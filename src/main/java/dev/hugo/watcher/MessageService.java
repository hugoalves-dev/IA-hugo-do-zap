package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class MessageService {
    public enum Acceptance { ACCEPTED, DUPLICATE, IGNORED, QUARANTINED }
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> NON_USER_CONTENT = Set.of("protocolMessage", "senderKeyDistributionMessage",
            "messageContextInfo", "reactionMessage", "pollUpdateMessage");
    private static final Set<String> MEDIA_FALLBACKS = Set.of("imageMessage", "videoMessage", "documentMessage");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IdentityService identities;
    private final WatcherProperties properties;
    private final ArchiveModeService archiveModes;

    public MessageService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                          IdentityService identities, WatcherProperties properties, ArchiveModeService archiveModes) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.identities = identities;
        this.properties = properties;
        this.archiveModes = archiveModes;
    }

    public Acceptance accept(IncomingMessage message) {
        Objects.requireNonNull(message, "message");
        return Objects.requireNonNull(transactions.execute(status -> acceptInTransaction(message)),
                "Message transaction returned no result");
    }

    private Acceptance acceptInTransaction(IncomingMessage message) {
        Instant now = Instant.now();
        Instant receivedAt = message.receivedAt() == null ? now : message.receivedAt();
        if (message.history() || !"notify".equals(message.upsertType()) || message.fromMe()
                || !validChatJid(message.chatJid()) || isStatusJid(message.chatJid())
                || isGroup(message.chatJid()) && !properties.isIncludeGroups()
                || !hasUserContent(message.content())) {
            return Acceptance.IGNORED;
        }

        String externalId = message.externalId().orElse(null);
        if (externalId == null || externalId.isBlank() || externalId.length() > 255) {
            quarantine("MESSAGE", externalId, "missing_or_invalid_external_id", now);
            return Acceptance.QUARANTINED;
        }

        String jid = message.chatJid();
        boolean group = isGroup(jid);
        String canonicalJid;
        if (group) {
            canonicalJid = jid;
        } else {
            var identity = identities.observe(new IdentityObservation(jid, message.alternateChatJid().map(Set::of).orElse(Set.of()),
                    message.alternateChatJid().filter(alias -> !alias.equals(jid)).isPresent()
                            ? IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR : IdentityEvidence.NONE));
            canonicalJid = identity.canonicalJid();
        }

        List<ChatSnapshot> snapshots = jdbc.query("""
                select archived, synchronized_snapshot, display_name, is_group, last_read_at
                from chat_snapshot where chat_jid=? for update
                """, (rs, row) -> new ChatSnapshot((Boolean) rs.getObject("archived"),
                rs.getBoolean("synchronized_snapshot"), rs.getString("display_name"), rs.getBoolean("is_group"),
                rs.getTimestamp("last_read_at") == null ? null : rs.getTimestamp("last_read_at").toInstant()), canonicalJid);
        ArchiveMode archiveMode = archiveModes.getMode();
        if (snapshots.isEmpty() && archiveMode == ArchiveMode.ALL) {
            snapshots = List.of(new ChatSnapshot(null, false, null, group, null));
        }
        if ((snapshots.isEmpty() || snapshots.getFirst().archived() == null || !snapshots.getFirst().synchronizedSnapshot())
                && archiveMode != ArchiveMode.ALL) {
            quarantine("MESSAGE", externalId, "archive_state_unknown_or_unsynchronized", now);
            return Acceptance.QUARANTINED;
        }
        ChatSnapshot chat = snapshots.getFirst();
        if (!archiveMode.accepts(chat.archived(), chat.synchronizedSnapshot())) return Acceptance.IGNORED;
        if (chat.group() && isBlockedGroup(chat.displayName())) {
            jdbc.update("delete from pending_notification where chat_jid=?", canonicalJid);
            return Acceptance.IGNORED;
        }

        String participant = "";
        if (group && message.participantJid().filter(value -> !value.isBlank()).isPresent()) {
            String participantJid = message.participantJid().orElseThrow();
            participant = identities.observe(new IdentityObservation(participantJid,
                    message.alternateParticipantJid().filter(alias -> !alias.equals(participantJid)).map(Set::of).orElse(Set.of()),
                    message.alternateParticipantJid().filter(alias -> !alias.equals(participantJid)).isPresent()
                            ? IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR : IdentityEvidence.NONE)).canonicalJid();
        }
        String dedupKey = sha256(canonicalJid + "\n" + externalId + "\n" + participant);
        String upstreamMessageKey = sha256(externalId);
        if (jdbc.update("""
                insert into processed_message(message_id, processed_at, canonical_chat_jid, upstream_message_key, canonical_participant_jid)
                values (?, ?, ?, ?, ?)
                on conflict do nothing
                """, dedupKey, Timestamp.from(now), canonicalJid, upstreamMessageKey, participant) == 0) return Acceptance.DUPLICATE;
        if (chat.lastReadAt() != null && !receivedAt.isAfter(chat.lastReadAt())) return Acceptance.IGNORED;

        String preview = extractPreview(message.content());
        NotificationPolicy.Kind policy = classifyPolicy(chat.displayName());
        Pending current = readPending(canonicalJid);
        Instant lastAt = current == null || receivedAt.isAfter(current.lastAt()) ? receivedAt : current.lastAt();
        String lastPreview = current == null || !receivedAt.isBefore(current.lastAt()) ? preview : current.preview();
        int retentionDays = Math.min(7, Math.max(1, properties.getRetentionDays()));
        Instant expiresAt = now.plusSeconds(retentionDays * 86_400L);
        Instant previewExpiresAt = current == null || !receivedAt.isBefore(current.lastAt())
                ? expiresAt : current.previewExpiresAt();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageCount", current == null ? 1 : current.count() + 1);
        payload.put("firstMessageAt", current == null ? receivedAt.toString() : current.firstAt().toString());
        payload.put("lastMessageAt", lastAt.toString());
        payload.put("preview", lastPreview);
        payload.put("previewExpiresAt", previewExpiresAt.toString());
        payload.put("isGroup", chat.group());
        payload.put("notificationPolicy", policy.name());
        jdbc.update("""
                insert into pending_notification(chat_jid, payload, due_at, updated_at, expires_at)
                values (?, ?::jsonb, ?, ?, ?)
                on conflict (chat_jid) do update set
                    payload=excluded.payload,
                    due_at=excluded.due_at,
                    updated_at=excluded.updated_at,
                    expires_at=excluded.expires_at
                """, canonicalJid, toJson(payload), Timestamp.from(lastAt), Timestamp.from(now),
                Timestamp.from(expiresAt));
        return Acceptance.ACCEPTED;
    }

    private Pending readPending(String jid) {
        List<Pending> rows = jdbc.query("""
                select coalesce((payload->>'messageCount')::integer, 0) as message_count,
                       payload->>'firstMessageAt' as first_message_at,
                       payload->>'lastMessageAt' as last_message_at,
                       payload->>'preview' as preview,
                       payload->>'previewExpiresAt' as preview_expires_at,
                       expires_at
                from pending_notification where chat_jid=? for update
                """, (rs, row) -> new Pending(rs.getInt("message_count"),
                Instant.parse(rs.getString("first_message_at")), Instant.parse(rs.getString("last_message_at")),
                rs.getString("preview"), parseInstant(rs.getString("preview_expires_at"), rs.getTimestamp("expires_at").toInstant())), jid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean isBlockedGroup(String name) {
        String normalizedName = normalize(name);
        return !normalizedName.isEmpty() && properties.getNeverNotifyGroupNames().stream()
                .map(MessageService::normalize).anyMatch(normalizedName::equals);
    }

    private NotificationPolicy.Kind classifyPolicy(String name) {
        String normalized = normalize(name);
        if (normalized.isEmpty() || normalized.matches(".*[@+0-9].*")) return NotificationPolicy.Kind.ANYTIME;
        return Pattern.compile(properties.getAllowedNameRegex(), Pattern.CASE_INSENSITIVE)
                .matcher(normalized).find() ? NotificationPolicy.Kind.WORK_ONLY : NotificationPolicy.Kind.ANYTIME;
    }

    private void quarantine(String kind, String externalId, String reason, Instant now) {
        QuarantineWriter.write(jdbc, kind, externalId, reason, now);
    }

    private static boolean hasUserContent(Map<String, Object> content) {
        return content != null && content.keySet().stream().anyMatch(key -> !NON_USER_CONTENT.contains(key));
    }

    private static boolean validChatJid(String jid) {
        return jid != null && !jid.isBlank() && jid.length() <= 255 && jid.contains("@");
    }

    private static boolean isStatusJid(String jid) {
        return jid.equals("status@broadcast") || jid.endsWith("@broadcast") || jid.endsWith("@newsletter");
    }

    private static boolean isGroup(String jid) { return jid.endsWith("@g.us"); }

    private static String extractPreview(Map<String, Object> content) {
        Map<String, Object> current = content == null ? Map.of() : content;
        List<String> wrappers = List.of("ephemeralMessage", "viewOnceMessage", "viewOnceMessageV2",
                "viewOnceMessageV2Extension", "documentWithCaptionMessage");
        for (int depth = 0; depth < 5; depth++) {
            Object conversation = current.get("conversation");
            if (conversation instanceof String text) return shorten(text);
            Object extended = current.get("extendedTextMessage");
            if (extended instanceof Map<?, ?> map && map.get("text") instanceof String text) return shorten(text);
            for (String media : List.of("imageMessage", "videoMessage", "documentMessage")) {
                Object value = current.get(media);
                if (value instanceof Map<?, ?> map && map.get("caption") instanceof String caption && !caption.isBlank()) {
                    return shorten(caption);
                }
            }
            Object audio = current.get("audioMessage");
            if (audio instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("ptt"))) return "[Voice message]";
            for (String media : MEDIA_FALLBACKS) if (current.containsKey(media)) return switch (media) {
                case "imageMessage" -> "[Image]";
                case "videoMessage" -> "[Video]";
                default -> "[Document]";
            };
            for (String media : List.of("audioMessage", "stickerMessage", "contactMessage", "contactsArrayMessage",
                    "locationMessage", "liveLocationMessage")) if (current.containsKey(media)) return switch (media) {
                case "audioMessage" -> "[Audio]";
                case "stickerMessage" -> "[Sticker]";
                case "contactMessage", "contactsArrayMessage" -> "[Contact]";
                default -> "[Location]";
            };
            String wrapper = wrappers.stream().filter(current::containsKey).findFirst().orElse(null);
            if (wrapper == null || !(current.get(wrapper) instanceof Map<?, ?> wrapped)
                    || !(wrapped.get("message") instanceof Map<?, ?> nested)) return "[Message]";
            current = stringMap(nested);
        }
        return "[Message]";
    }

    private static Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> { if (key instanceof String text) result.put(text, value); });
        return result;
    }

    private static String shorten(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= 140) return normalized;
        return normalized.substring(0, normalized.offsetByCodePoints(0, 140));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "").trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String toJson(Map<String, Object> payload) {
        try { return JSON.writeValueAsString(payload); }
        catch (JacksonException ex) { throw new IllegalArgumentException("Could not encode notification preview", ex); }
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null) return fallback;
        try { return Instant.parse(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private record ChatSnapshot(Boolean archived, boolean synchronizedSnapshot, String displayName, boolean group,
                                Instant lastReadAt) { }
    private record Pending(int count, Instant firstAt, Instant lastAt, String preview, Instant previewExpiresAt) { }
}
