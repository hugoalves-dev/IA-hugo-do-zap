package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
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
import dev.hugo.watcher.notification.IntelligentAlertService;

/** Persists notification payloads before Telegram delivery and acknowledges snapshots conditionally. */
public class NotificationService {
    public static final String ARCHIVED_MESSAGES = "archived_messages";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final System.Logger LOG = System.getLogger(NotificationService.class.getName());
    private static final long MAX_RETENTION_SECONDS = 7 * 86_400L;
    private static final DateTimeFormatter LOCAL_ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");

    private final NotificationQueueQueryRepository queries;
    private final TransactionTemplate transactions;
    private final NotificationOutboxRepository outbox;
    private final TelegramTransport telegram;
    private final TelegramMessageFormatter telegramFormatter;
    private final WatcherProperties properties;
    private final Clock clock;
    private final ArchiveModeService archiveModes;
    private final IntelligentAlertService intelligentAlerts;
    private final dev.hugo.watcher.notification.DecisionDeliveryCoordinator deliveries;
    private final AtomicBoolean flushing = new AtomicBoolean();

    public NotificationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                               NotificationOutboxRepository outbox, TelegramTransport telegram,
                               TelegramMessageFormatter telegramFormatter, WatcherProperties properties,
                               Clock clock, ArchiveModeService archiveModes, IntelligentAlertService intelligentAlerts) {
        this(new NotificationQueueQueryRepository(jdbc), manager, outbox, telegram,
                telegramFormatter, properties, clock, archiveModes, intelligentAlerts,
                new dev.hugo.watcher.notification.DecisionDeliveryCoordinator(jdbc, manager));
    }

    public NotificationService(NotificationQueueQueryRepository queries, PlatformTransactionManager manager,
                               NotificationOutboxRepository outbox, TelegramTransport telegram,
                               TelegramMessageFormatter telegramFormatter, WatcherProperties properties,
                               Clock clock, ArchiveModeService archiveModes, IntelligentAlertService intelligentAlerts,
                               dev.hugo.watcher.notification.DecisionDeliveryCoordinator deliveries) {
        this.queries = queries;
        this.transactions = new TransactionTemplate(manager);
        this.outbox = outbox;
        this.telegram = telegram;
        this.telegramFormatter = telegramFormatter;
        this.properties = properties;
        this.clock = clock;
        this.archiveModes = archiveModes;
        this.intelligentAlerts = intelligentAlerts;
        this.deliveries = deliveries;
    }

    public NotificationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                               NotificationOutboxRepository outbox, TelegramTransport telegram,
                               TelegramMessageFormatter telegramFormatter, WatcherProperties properties,
                               Clock clock, ArchiveModeService archiveModes) {
        this(jdbc, manager, outbox, telegram, telegramFormatter, properties, clock, archiveModes, null);
    }

    public void flush(Instant now) {
        if (now == null || !flushing.compareAndSet(false, true)) return;
        try {
            transactions.executeWithoutResult(status -> enqueueArchivedBatch(now));
            if (outbox.rabbitDispatchEnabled()) {
                outbox.publishPendingActions();
                return;
            }
            for (int sent = 0; sent < 20; sent++) {
                Instant claimTime = clock.instant();
                OutboxAction action = outbox.claimNext(claimTime);
                if (action == null) return;
                deliverClaimed(action);
            }
        } catch (RuntimeException ex) {
            if (outbox.rabbitDispatchEnabled()) throw ex;
            LOG.log(System.Logger.Level.ERROR, "Notification flush failed: error={0}", ex.getClass().getSimpleName());
        } finally {
            flushing.set(false);
        }
    }

    /** Generates due notification state, then republishes pending state after an upstream replay. */
    public void flushAfterInbound(Instant now) {
        flush(now);
        outbox.publishPendingActions();
    }

    /** Delivers exactly the action named by a RabbitMQ notification message. */
    public boolean deliverQueuedAction(String actionKey, Instant now) {
        OutboxAction action = outbox.claimByKey(actionKey, now);
        return action != null && deliverClaimed(action);
    }

    private boolean deliverClaimed(OutboxAction action) {
        try {
            if (isConnectionAlert(action)) {
                TelegramDeliveryResult result = isCurrentConnectionAlert(action)
                        ? telegram.send(telegramFormatter.format(action)) : TelegramDeliveryResult.failure();
                if (result.delivered()) {
                    outbox.acknowledge(action, clock.instant());
                    return true;
                } else if (!isCurrentConnectionAlert(action)) {
                    outbox.cancelClaimedConnectionAlert(action, clock.instant());
                    return false;
                }
                else {
                    outbox.retry(action, clock.instant(), "DeliveryFailure", result.rateLimited(), result.retryAfter());
                    throwRetryIfQueued(action, result.rateLimited(), result.retryAfter());
                    return false;
                }
            } else if (IntelligentAlertService.ACTION_TYPE.equals(action.actionType())) {
                OutboxAction latest = intelligentAlerts != null && intelligentAlerts.revalidate(action, clock.instant())
                        ? action : null;
                if (latest == null) { outbox.suppress(action, clock.instant()); return false; }
                java.util.UUID decisionId = java.util.UUID.fromString(action.actionKey().substring("intelligent_alert:".length()));
                java.util.UUID token = java.util.UUID.fromString(action.leaseToken());
                if (!deliveries.begin(decisionId, "TELEGRAM", token, clock.instant())) {
                    outbox.retry(action, clock.instant(), "ConversationDispatchUnresolved");
                    throwRetryIfQueued(action, false, null);
                    return false;
                }
                TelegramDeliveryResult result;
                try {
                    result = telegram.sendHtml(telegramFormatter.format(latest));
                    if (result == null || result.ambiguous()) throw new IllegalStateException("ambiguous transport result");
                } catch (RuntimeException ambiguousTransport) {
                    transactions.executeWithoutResult(status -> {
                        deliveries.finish(decisionId, "TELEGRAM", token, "UNKNOWN", clock.instant());
                        outbox.retry(action, clock.instant(), "AmbiguousDelivery");
                    });
                    return false;
                }
                if (result.delivered()) {
                    outbox.acknowledge(latest, clock.instant());
                    return true;
                }
                else {
                    TelegramDeliveryResult rejected = result;
                    transactions.executeWithoutResult(status -> {
                        deliveries.finish(decisionId, "TELEGRAM", token, "RETRYABLE", clock.instant());
                        outbox.retry(action, clock.instant(), "DeliveryFailure", rejected.rateLimited(), rejected.retryAfter());
                    });
                    throwRetryIfQueued(action, rejected.rateLimited(), rejected.retryAfter());
                    return false;
                }
            } else {
                OutboxAction latest = revalidateBeforeDelivery(action, clock.instant());
                if (latest == null && outbox.rabbitDispatchEnabled()
                        && shouldDeferForWorkWindow(action, clock.instant())) {
                    Instant nextWindow = nextWorkWindowStart(clock.instant());
                    outbox.defer(action, clock.instant(), nextWindow);
                    long delayMillis = Math.max(1_000, java.time.Duration.between(clock.instant(), nextWindow).toMillis());
                    throw new NotificationDeferredException(delayMillis,
                            NotificationDeferredException.Reason.WORK_WINDOW, nextWindow);
                }
                TelegramDeliveryResult result = latest == null ? TelegramDeliveryResult.failure()
                        : ARCHIVED_MESSAGES.equals(latest.actionType())
                        ? telegram.sendHtml(telegramFormatter.format(latest))
                        : "telegram_admin_reply".equals(latest.actionType())
                        ? (telegramFormatter.isHtmlAdmin(latest)
                            ? telegram.sendAdminHtml(telegramFormatter.formatAdminHtml(latest), latest.payload())
                            : telegram.sendAdmin(telegramFormatter.format(latest), latest.payload()))
                        : "telegram_admin_edit".equals(latest.actionType())
                        ? (telegramFormatter.isHtmlAdmin(latest)
                            ? telegram.editAdminHtml(telegramFormatter.formatAdminHtml(latest), latest.payload())
                            : telegram.editAdmin(telegramFormatter.format(latest), latest.payload()))
                        : ("simulation_daily_summary".equals(latest.actionType())
                                || "simulation_reply_preview".equals(latest.actionType()))
                        ? telegram.sendHtml(telegramFormatter.format(latest))
                        : telegram.send(telegramFormatter.format(latest));
                if (result.delivered()) {
                    outbox.acknowledge(latest, clock.instant());
                    return true;
                } else if (latest == null) {
                    outbox.suppress(action, clock.instant());
                    return false;
                }
                else {
                    outbox.retry(action, clock.instant(), "DeliveryFailure", result.rateLimited(), result.retryAfter());
                    throwRetryIfQueued(action, result.rateLimited(), result.retryAfter());
                    return false;
                }
            }
        } catch (DataAccessException ex) {
            outbox.retry(action, clock.instant(), "StateReadFailure");
            throwRetryIfQueued(action, false, null);
            return false;
        }
    }

    private void throwRetryIfQueued(OutboxAction action, boolean rateLimited, java.time.Duration retryAfter) {
        if (outbox.rabbitDispatchEnabled())
            throw new NotificationRetryException("Telegram delivery is retryable",
                    retryDelayMillis(action, rateLimited, retryAfter), rateLimited);
    }

    private static long retryDelayMillis(OutboxAction action, boolean rateLimited, java.time.Duration retryAfter) {
        if (rateLimited) {
            java.time.Duration delay = retryAfter == null || retryAfter.isNegative() || retryAfter.isZero()
                    ? java.time.Duration.ofSeconds(60) : retryAfter;
            return Math.max(1_000, delay.plusMillis(250).toMillis());
        }
        int exponent = Math.min(7, Math.max(0, action.attempts() - 1));
        return Math.min(15 * 60_000L, (10L << exponent) * 1_000L);
    }

    private boolean shouldDeferForWorkWindow(OutboxAction action, Instant now) {
        if (!ARCHIVED_MESSAGES.equals(action.actionType()) || inWorkWindow(now, ZoneId.of(properties.getTimezone()))) return false;
        Map<String, Object> payload = readObject(action.payload());
        List<Map<String, Object>> chats = readObjectList(payload.get("chats"));
        return !chats.isEmpty() && chats.stream().allMatch(chat -> "WORK_ONLY".equals(chat.get("policy")));
    }

    private Instant nextWorkWindowStart(Instant now) {
        ZoneId zone = ZoneId.of(properties.getTimezone());
        java.time.LocalDate localDate = now.atZone(zone).toLocalDate();
        for (int offset = 0; offset <= 14; offset++) {
            java.time.LocalDate date = localDate.plusDays(offset);
            if (!properties.getNotificationDays().contains(date.getDayOfWeek().getValue())) continue;
            for (WatcherProperties.TimeWindow window : properties.getNotificationWindows()) {
                Instant candidate = date.atTime(window.start()).atZone(zone).toInstant();
                if (candidate.isAfter(now)) return candidate;
            }
        }
        throw new IllegalStateException("no future work window is configured");
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
            ArchiveMode mode = archiveModes.getMode();
            Map<String, PendingRow> current = new LinkedHashMap<>();
            for (PendingRow row : readPending(now, mode)) current.put(row.chatJid(), row);

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
                chat.put("displayName", displayNameForTelegram(row));
                chat.remove("phoneJid");
                if (!row.group() && validatedPhone(row.phoneJid()) != null) chat.put("phoneJid", row.phoneJid());
                chat.put("isGroup", row.group());
                chat.put("policy", policy);
                allowedChats.add(chat);
                allowedSnapshots.add(snapshot);
                totalMessages += integer(chat.get("messageCount"));
            }
            if (allowedChats.isEmpty()) return null;

            payload.put("archiveMode", mode.name());
            payload.put("chats", allowedChats);
            payload.put("totalChats", allowedChats.size());
            payload.put("totalMessages", totalMessages);
            int included = telegramFormatter.includedArchivedChats(writeJson(payload));
            if (included < 1) return null;
            if (included < allowedChats.size()) {
                allowedChats = new ArrayList<>(allowedChats.subList(0, included));
                allowedSnapshots = new ArrayList<>(allowedSnapshots.subList(0, included));
                payload.put("chats", allowedChats);
                payload.put("totalChats", included);
                payload.put("totalMessages", allowedChats.stream().mapToInt(chat -> integer(chat.get("messageCount"))).sum());
            }
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
            return queries.currentOutageGeneration(expectedGeneration);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private void enqueueArchivedBatch(Instant now) {
        if (outbox.hasActiveAction(ARCHIVED_MESSAGES, now)) return;
        ZoneId zone = ZoneId.of(properties.getTimezone());
        boolean inWorkWindow = inWorkWindow(now, zone);
        ArchiveMode mode = archiveModes.getMode();
        List<PendingRow> pending = readPending(now, mode);
        List<LegacyChat> chats = new ArrayList<>();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        int deferredQueued = 0;
        Instant sensitiveExpiry = now.plusSeconds(Math.min(MAX_RETENTION_SECONDS,
                Math.min(7, Math.max(1, properties.getRetentionDays())) * 86_400L));

        for (PendingRow row : pending) {
            if (row == null) continue;
            if (row.group() && !properties.isIncludeGroups()) continue;
            String displayName = resolveDisplayName(row);
            if (row.group() && isBlockedGroup(displayName)) {
                queries.discardBlockedGroup(row.chatJid());
                continue;
            }
            String policy = isWorkOnly(displayName) ? "WORK_ONLY" : "ANYTIME";
            if ("WORK_ONLY".equals(policy) && !inWorkWindow) {
                if (outbox.rabbitDispatchEnabled() && deferredQueued < properties.getMaxBatchSize()
                        && enqueueDeferredChat(row, displayName, mode, zone, now)) deferredQueued++;
                continue;
            }
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
        payload.put("archiveMode", mode.name());
        payload.put("generatedAt", localIso(now, zone));
        payload.put("totalChats", chats.size());
        payload.put("totalMessages", chats.stream().mapToInt(LegacyChat::messageCount).sum());
        payload.put("chats", chats.stream().map(LegacyChat::payload).toList());

        String payloadJson = writeJson(payload);
        String snapshotJson = writeJson(snapshots);
        Map<String, Object> stablePayload = new LinkedHashMap<>(payload);
        stablePayload.remove("generatedAt");
        outbox.enqueue(ARCHIVED_MESSAGES + ":" + sha256(mode.name() + "\n" + snapshotJson
                        + "\n" + writeJson(stablePayload)), ARCHIVED_MESSAGES,
                payloadJson, snapshotJson, now, sensitiveExpiry);
        LOG.log(System.Logger.Level.INFO, "Archived notification queued: chats={0}, messages={1}",
                chats.size(), chats.stream().mapToInt(LegacyChat::messageCount).sum());
    }

    private boolean enqueueDeferredChat(PendingRow row, String displayName, ArchiveMode mode, ZoneId zone, Instant now) {
        String actionKey = ARCHIVED_MESSAGES + ":deferred:" + sha256(mode.name() + "\n" + row.chatJid()
                + "\n" + row.generation());
        if (outbox.hasActiveActionKey(actionKey)) return false;

        String policy = "WORK_ONLY";
        LegacyChat chat = toLegacyChat(row, displayName, policy, zone, now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", ARCHIVED_MESSAGES);
        payload.put("archiveMode", mode.name());
        payload.put("generatedAt", localIso(now, zone));
        payload.put("totalChats", 1);
        payload.put("totalMessages", row.messageCount());
        payload.put("chats", List.of(chat.payload()));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("chat_jid", row.chatJid());
        snapshot.put("pending_generation", row.generation());
        snapshot.put("message_count", row.messageCount());
        snapshot.put("first_message_at", row.firstMessageAt().toString());
        snapshot.put("last_message_at", row.lastMessageAt().toString());
        snapshot.put("updated_at", row.updatedAt().toString());

        Instant expiry = min(now.plusSeconds(Math.min(MAX_RETENTION_SECONDS,
                Math.min(7, Math.max(1, properties.getRetentionDays())) * 86_400L)), row.expiresAt());
        if (row.previewExpiry() != null && row.previewExpiry().isAfter(now)) expiry = min(expiry, row.previewExpiry());
        Instant nextWindow = nextWorkWindowStart(now);
        outbox.enqueue(actionKey, ARCHIVED_MESSAGES, writeJson(payload), writeJson(List.of(snapshot)),
                now, expiry, nextWindow);
        return true;
    }

    private List<PendingRow> readPending(Instant now, ArchiveMode mode) {
        List<PendingRow> rows = queries.duePending(now, (rs, rowNum) -> {
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
        });
        return rows.stream().filter(row -> mode.accepts(row.archived(), row.synchronizedSnapshot())).toList();
    }

    private LegacyChat toLegacyChat(PendingRow row, String displayName, String policy, ZoneId zone, Instant now) {
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("jid", row.canonicalJid());
        chat.put("displayName", displayNameForTelegram(row));
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

    private static String displayNameForTelegram(PendingRow row) {
        String name = row.group() ? row.chatName() : (validName(row.identityName()) ? row.identityName() : row.chatName());
        if (validName(name) && !name.trim().matches("^\\+?\\d{7,}$")) return name.trim();
        String phone = row.group() ? null : validatedPhone(row.phoneJid());
        return phone == null ? (row.group() ? "Grupo" : "Conversa") : "+" + phone;
    }

    private static String validatedPhone(String jid) {
        if (jid == null || !jid.matches("(?i)^[1-9][0-9]{7,14}(?::[0-9]+)?@s\\.whatsapp\\.net$")) return null;
        return phoneNumber(jid);
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
    private record LegacyChat(int messageCount, Map<String, Object> payload) { }
}
