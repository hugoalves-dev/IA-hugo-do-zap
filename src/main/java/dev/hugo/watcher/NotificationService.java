package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Persists notification payloads before Telegram delivery and acknowledges snapshots conditionally. */
@Service
public class NotificationService {
    public static final String ARCHIVED_MESSAGES = "archived_messages";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(NotificationService.class.getName());
    private static final long MAX_RETENTION_SECONDS = 7 * 86_400L;
    private static final DateTimeFormatter LOCAL_ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NotificationOutboxRepository outbox;
    private final TelegramTransport telegram;
    private final TelegramMessageFormatter telegramFormatter;
    private final WatcherProperties properties;
    private final Clock clock;
    private final ArchiveModeService archiveModes;
    private final AtomicBoolean flushing = new AtomicBoolean();

    public NotificationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                               NotificationOutboxRepository outbox, TelegramTransport telegram,
                               TelegramMessageFormatter telegramFormatter, WatcherProperties properties,
                               Clock clock, ArchiveModeService archiveModes) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.outbox = outbox;
        this.telegram = telegram;
        this.telegramFormatter = telegramFormatter;
        this.properties = properties;
        this.clock = clock;
        this.archiveModes = archiveModes;
    }

    public void flush(Instant now) {
        if (now == null || !flushing.compareAndSet(false, true)) return;
        try {
            transactions.executeWithoutResult(status -> enqueueArchivedBatch(now));
            for (int sent = 0; sent < 20; sent++) {
                Instant claimTime = clock.instant();
                OutboxAction action = outbox.claimNext(claimTime);
                if (action == null) return;
                try {
                    if (isConnectionAlert(action)) {
                        TelegramDeliveryResult result = isCurrentConnectionAlert(action)
                                ? telegram.send(telegramFormatter.format(action)) : TelegramDeliveryResult.failure();
                        if (result.delivered()) outbox.acknowledge(action, clock.instant());
                        else if (!isCurrentConnectionAlert(action)) outbox.cancelClaimedConnectionAlert(action, clock.instant());
                        else outbox.retry(action, clock.instant(), "DeliveryFailure", result.rateLimited(), result.retryAfter());
                    } else {
                        OutboxAction latest = revalidateBeforeDelivery(action, clock.instant());
                        TelegramDeliveryResult result = latest == null ? TelegramDeliveryResult.failure()
                                : telegram.send(telegramFormatter.format(latest));
                        if (result.delivered()) outbox.acknowledge(latest, clock.instant());
                        else if (latest == null) outbox.suppress(action, clock.instant());
                        else outbox.retry(action, clock.instant(), "DeliveryFailure", result.rateLimited(), result.retryAfter());
                    }
                } catch (DataAccessException ex) {
                    // Unknown policy/state is retryable, never proof that an alert is obsolete.
                    outbox.retry(action, clock.instant(), "StateReadFailure");
                }
            }
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.ERROR, "Notification flush failed: error={0}", ex.getClass().getSimpleName());
        } finally {
            flushing.set(false);
        }
    }

    /** Rechecks current archive, sync, group, name, and work-window policy immediately before HTTP delivery. */
    private OutboxAction revalidateBeforeDelivery(OutboxAction action, Instant now) {
        if (!ARCHIVED_MESSAGES.equals(action.actionType())) return action;
        try {
            Map<String, Object> payload = readObject(action.payload());
            List<Map<String, Object>> chats = readObjectList(payload.get("chats"));
            List<Map<String, Object>> snapshots = readObjectList(action.pendingSnapshot());
            if (chats.size() != snapshots.size()) return null;

            ZoneId zone = ZoneId.of(properties.getTimezone());
            boolean inWindow = inWorkWindow(now, zone);
            Map<String, PendingRow> current = new LinkedHashMap<>();
            for (PendingRow row : readPending(now)) current.put(row.chatJid(), row);

            List<Map<String, Object>> allowedChats = new ArrayList<>();
            List<Map<String, Object>> allowedSnapshots = new ArrayList<>();
            int totalMessages = 0;
            for (int i = 0; i < snapshots.size(); i++) {
                Map<String, Object> snapshot = snapshots.get(i);
                String chatJid = snapshot.get("chat_jid") instanceof String value ? value : null;
                PendingRow row = chatJid == null ? null : current.get(chatJid);
                if (row == null || !row.generation().equals(snapshot.get("pending_generation"))) continue;
                if (row.group() && !properties.isIncludeGroups()) continue;
                String displayName = resolveDisplayName(row);
                if (row.group() && isBlockedGroup(displayName)) continue;
                String policy = isWorkOnly(displayName) ? "WORK_ONLY" : "ANYTIME";
                if ("WORK_ONLY".equals(policy) && !inWindow) continue;

                Map<String, Object> chat = new LinkedHashMap<>(chats.get(i));
                chat.put("displayName", displayName);
                chat.put("isGroup", row.group());
                chat.put("policy", policy);
                allowedChats.add(chat);
                allowedSnapshots.add(snapshot);
                totalMessages += integer(chat.get("messageCount"));
            }
            if (allowedChats.isEmpty()) return null;

            payload.put("chats", allowedChats);
            payload.put("totalChats", allowedChats.size());
            payload.put("totalMessages", totalMessages);
            return new OutboxAction(action.id(), action.actionKey(), action.actionType(), writeJson(payload),
                    writeJson(allowedSnapshots), action.attempts(), action.createdAt(),
                    action.sensitiveExpiresAt(), action.leaseToken());
        } catch (DataAccessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Archived notification suppressed: invalid queued payload");
            return null;
        }
    }

    private boolean isConnectionAlert(OutboxAction action) {
        return action != null && ConnectionService.ACTION_TYPE.equals(action.actionType());
    }

    /** A connection alert is deliverable only for the still-active outage generation. */
    private boolean isCurrentConnectionAlert(OutboxAction action) {
        String prefix = ConnectionService.ACTION_TYPE + ":";
        if (!isConnectionAlert(action) || !action.actionKey().startsWith(prefix)) return false;
        try {
            long expectedGeneration = Long.parseLong(action.actionKey().substring(prefix.length()));
            List<ConnectionState> current = jdbc.query("""
                    select connection_state,outage_generation from watcher_connection_state where singleton_id=1
                    """, (rs, row) -> new ConnectionState(rs.getString("connection_state"), rs.getLong("outage_generation")));
            return !current.isEmpty() && "DISCONNECTED".equals(current.getFirst().connectionState())
                    && current.getFirst().generation() == expectedGeneration;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private void enqueueArchivedBatch(Instant now) {
        if (outbox.hasActiveAction(ARCHIVED_MESSAGES, now)) return;
        ZoneId zone = ZoneId.of(properties.getTimezone());
        boolean inWorkWindow = inWorkWindow(now, zone);
        List<PendingRow> pending = readPending(now);
        List<LegacyChat> chats = new ArrayList<>();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        Instant sensitiveExpiry = now.plusSeconds(Math.min(MAX_RETENTION_SECONDS,
                Math.min(7, Math.max(1, properties.getRetentionDays())) * 86_400L));

        for (PendingRow row : pending) {
            if (row == null) continue;
            if (row.group() && !properties.isIncludeGroups()) continue;
            String displayName = resolveDisplayName(row);
            if (row.group() && isBlockedGroup(displayName)) {
                jdbc.update("delete from pending_notification where chat_jid=?", row.chatJid());
                continue;
            }
            String policy = isWorkOnly(displayName) ? "WORK_ONLY" : "ANYTIME";
            if ("WORK_ONLY".equals(policy) && !inWorkWindow) continue;
            if (chats.size() >= properties.getMaxBatchSize()) break;

            chats.add(toLegacyChat(row, displayName, policy, zone, now));
            sensitiveExpiry = min(sensitiveExpiry, row.expiresAt());
            if (row.previewExpiry() != null && row.previewExpiry().isAfter(now)
                    && row.preview() != null && !row.preview().isBlank()) {
                sensitiveExpiry = min(sensitiveExpiry, row.previewExpiry());
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("chat_jid", row.chatJid());
            snapshot.put("pending_generation", row.generation());
            snapshot.put("message_count", row.messageCount());
            snapshot.put("first_message_at", row.firstMessageAt().toString());
            snapshot.put("last_message_at", row.lastMessageAt().toString());
            snapshot.put("updated_at", row.updatedAt().toString());
            snapshots.add(snapshot);
        }
        if (chats.isEmpty()) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", ARCHIVED_MESSAGES);
        payload.put("generatedAt", localIso(now, zone));
        payload.put("totalChats", chats.size());
        payload.put("totalMessages", chats.stream().mapToInt(LegacyChat::messageCount).sum());
        payload.put("chats", chats.stream().map(LegacyChat::payload).toList());

        String payloadJson = writeJson(payload);
        String snapshotJson = writeJson(snapshots);
        Map<String, Object> stablePayload = new LinkedHashMap<>(payload);
        stablePayload.remove("generatedAt");
        outbox.enqueue(ARCHIVED_MESSAGES + ":" + sha256(archiveModes.getMode().name() + "\n" + snapshotJson
                        + "\n" + writeJson(stablePayload)), ARCHIVED_MESSAGES,
                payloadJson, snapshotJson, now, sensitiveExpiry);
        LOG.log(System.Logger.Level.INFO, "Archived notification queued: chats={0}, messages={1}",
                chats.size(), chats.stream().mapToInt(LegacyChat::messageCount).sum());
    }

    private List<PendingRow> readPending(Instant now) {
        List<PendingRow> rows = jdbc.query("""
                select p.chat_jid, p.generation::text as pending_generation, p.payload::text as payload, p.updated_at, p.expires_at,
                       s.archived, coalesce(s.synchronized_snapshot, false) as synchronized_snapshot,
                       s.display_name as chat_name, coalesce(s.is_group, p.chat_jid like '%@g.us') as is_group,
                       coalesce(alias.canonical_identity, p.chat_jid) as canonical_jid,
                       identity.display_name as identity_name, identity.phone_jid,
                       identity.lid_jid, identity.username
                from pending_notification p
                left join chat_snapshot s on s.chat_jid=p.chat_jid
                left join identity_aliases alias on alias.alias=p.chat_jid
                left join contact_identity identity
                    on identity.canonical_jid=coalesce(alias.canonical_identity, p.chat_jid)
                where p.due_at <= ? and p.expires_at > ?
                order by p.due_at,p.chat_jid
                """, (rs, rowNum) -> {
            String payloadText = rs.getString("payload");
            Map<String, Object> payload = readObject(payloadText);
            Instant last = parseInstant(payload.get("lastMessageAt"));
            Instant first = parseInstant(payload.get("firstMessageAt"));
            if (last == null || first == null) return null;
            Instant previewExpiry = parseInstant(payload.get("previewExpiresAt"));
            String preview = payload.get("preview") instanceof String text ? text : null;
            if (previewExpiry == null || !previewExpiry.isAfter(now)) preview = null;
            String canonicalJid = rs.getString("canonical_jid");
            String phoneJid = optionalPn(rs.getString("phone_jid"));
            if (phoneJid == null) phoneJid = optionalPn(canonicalJid);
            String lidJid = optionalJid(rs.getString("lid_jid"), "@lid");
            if (lidJid == null) lidJid = optionalJid(canonicalJid, "@lid");
            String username = cleanUsername(rs.getString("username"));
            if (username == null && canonicalJid.startsWith("@")) username = cleanUsername(canonicalJid);
            return new PendingRow(rs.getString("chat_jid"), canonicalJid,
                    rs.getString("chat_name"), rs.getString("identity_name"), rs.getBoolean("is_group"),
                    phoneJid, lidJid, username, integer(payload.get("messageCount")),
                    first, last, preview, previewExpiry, rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("pending_generation"),
                (Boolean) rs.getObject("archived"), rs.getBoolean("synchronized_snapshot"));
        }, Timestamp.from(now), Timestamp.from(now));
        ArchiveMode mode = archiveModes.getMode();
        return rows.stream().filter(row -> mode.accepts(row.archived(), row.synchronizedSnapshot())).toList();
    }

    private LegacyChat toLegacyChat(PendingRow row, String displayName, String policy, ZoneId zone, Instant now) {
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("jid", row.canonicalJid());
        chat.put("displayName", displayName);
        if (row.phoneJid() != null) {
            chat.put("phoneJid", row.phoneJid());
            chat.put("phoneNumber", phoneNumber(row.phoneJid()));
        }
        if (row.lidJid() != null) chat.put("lidJid", row.lidJid());
        if (row.username() != null) chat.put("username", row.username());
        if (row.phoneJid() != null && row.lidJid() != null) {
            chat.put("remoteJidAlt", row.canonicalJid().equals(row.lidJid()) ? row.phoneJid() : row.lidJid());
        }
        chat.put("messageCount", row.messageCount());
        chat.put("firstMessageAt", localIso(row.firstMessageAt(), zone));
        chat.put("lastMessageAt", localIso(row.lastMessageAt(), zone));
        chat.put("lastMessagePreview", row.preview());
        chat.put("isGroup", row.group());
        chat.put("policy", policy);
        return new LegacyChat(row.messageCount(), chat);
    }

    private boolean inWorkWindow(Instant now, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        if (!properties.getNotificationDays().contains(local.getDayOfWeek().getValue())) return false;
        LocalTime time = local.toLocalTime();
        return properties.getNotificationWindows().stream()
                .anyMatch(window -> !time.isBefore(window.start()) && time.isBefore(window.end()));
    }

    private boolean isWorkOnly(String name) {
        String normalized = normalize(name);
        if (normalized.isEmpty() || normalized.matches(".*[@+0-9].*")) return false;
        return Pattern.compile(properties.getAllowedNameRegex(), Pattern.CASE_INSENSITIVE).matcher(normalized).find();
    }

    private boolean isBlockedGroup(String name) {
        String normalized = normalize(name);
        return !normalized.isEmpty() && properties.getNeverNotifyGroupNames().stream()
                .map(NotificationService::normalize).anyMatch(normalized::equals);
    }

    private static String resolveDisplayName(PendingRow row) {
        String existing = validName(row.identityName()) ? row.identityName() : row.chatName();
        if (validName(existing)) return existing.trim();
        if (row.username() != null) return row.username();
        if (row.phoneJid() != null) return "+" + phoneNumber(row.phoneJid());
        return row.canonicalJid();
    }

    private static boolean validName(String name) {
        if (name == null || name.isBlank()) return false;
        String value = name.trim();
        return !value.matches("(?i)^\\+?\\d+(?::\\d+)?@(lid|s\\.whatsapp\\.net|g\\.us|broadcast|hosted\\.lid|hosted)$")
                && !value.matches("^\\d{7,}$")
                && !value.matches("^@[\\p{L}\\p{N}._-]+$")
                && !value.matches("(?i).*@(lid|s\\.whatsapp\\.net|g\\.us|broadcast|hosted\\.lid|hosted)$");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "")
                .trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String optionalPn(String value) {
        return value != null && value.matches("(?i)^\\d{1,15}(?::\\d+)?@s\\.whatsapp\\.net$") ? value : null;
    }

    private static String optionalJid(String value, String suffix) {
        if (value != null && value.toLowerCase(Locale.ROOT).endsWith(suffix)) return value;
        return null;
    }

    private static String cleanUsername(String value) {
        if (value == null) return null;
        String cleaned = value.trim().replaceAll("^@", "").replaceAll("@+$", "");
        return cleaned.matches("[\\p{L}\\p{N}._-]{1,64}") ? cleaned : null;
    }

    private static String phoneNumber(String jid) {
        if (jid == null) return null;
        return jid.substring(0, jid.indexOf('@')).split(":", 2)[0];
    }

    private static int integer(Object value) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try { return Math.max(0, Integer.parseInt(String.valueOf(value))); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static Instant parseInstant(Object value) {
        if (!(value instanceof String text)) return null;
        try { return Instant.parse(text); }
        catch (RuntimeException ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObject(String value) {
        try { return JSON.readValue(value, Map.class); }
        catch (RuntimeException ex) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readObjectList(Object value) {
        if (!(value instanceof String text)) {
            if (value instanceof List<?> list) return (List<Map<String, Object>>) list;
            return List.of();
        }
        try { return JSON.readValue(text, List.class); }
        catch (RuntimeException ex) { return List.of(); }
    }

    private static String localIso(Instant instant, ZoneId zone) {
        return LOCAL_ISO.withZone(zone).format(instant);
    }

    private static String writeJson(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Could not encode outbox payload", ex); }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }

    private record PendingRow(String chatJid, String canonicalJid, String chatName, String identityName,
                              boolean group, String phoneJid, String lidJid, String username, int messageCount,
                              Instant firstMessageAt, Instant lastMessageAt, String preview, Instant previewExpiry,
                              Instant updatedAt, Instant expiresAt, String generation, Boolean archived,
                              boolean synchronizedSnapshot) { }
    private record ConnectionState(String connectionState, long generation) { }
    private record LegacyChat(int messageCount, Map<String, Object> payload) { }
}
