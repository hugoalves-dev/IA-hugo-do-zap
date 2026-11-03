package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import dev.hugo.watcher.IdentityMergeRepository.AliasRow;
import dev.hugo.watcher.IdentityMergeRepository.ContactRow;
import dev.hugo.watcher.IdentityMergeRepository.ChatSnapshotRow;
import dev.hugo.watcher.IdentityMergeRepository.PendingRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

@Service
public class IdentityService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern PHONE_JID = Pattern.compile("^(\\d{1,15})(?::\\d+)?@s\\.whatsapp\\.net$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LID_JID = Pattern.compile("^.+@lid$", Pattern.CASE_INSENSITIVE);
    private static final Pattern USERNAME = Pattern.compile("^[\\p{L}\\p{N}._-]{1,64}$");
    private static final Pattern TECHNICAL_NAME = Pattern.compile(
            "^(?:\\+?\\d+(?::\\d+)?@(lid|s\\.whatsapp\\.net|g\\.us|broadcast|hosted\\.lid|hosted)|\\d{7,}|@[\\p{L}\\p{N}._-]+)$|@(?:lid|s\\.whatsapp\\.net|g\\.us|broadcast|hosted\\.lid|hosted)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private final IdentityMergeRepository identityStorage;
    private final TransactionTemplate transactions;

    /** Legacy test adapter; production uses the managed repository constructor. */
    public IdentityService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(new IdentityMergeRepository(jdbc), manager);
    }

    public IdentityService(IdentityMergeRepository identityStorage, PlatformTransactionManager manager) {
        this.identityStorage = identityStorage;
        this.transactions = new TransactionTemplate(manager);
    }

    public CanonicalIdentity observe(IdentityObservation observation) {
        Objects.requireNonNull(observation, "observation");
        String observedJid = requireJid(observation.observedJid());
        Set<String> identities = new LinkedHashSet<>();
        identities.add(observedJid);
        if (observation.evidence() == IdentityEvidence.EXPLICIT_ALTERNATE_JID_PAIR) {
            for (String alias : observation.aliases()) identities.add(requireJid(alias));
        }

        return Objects.requireNonNull(transactions.execute(status -> {
            if (identities.size() == 1) return observeSingle(observedJid);
            CanonicalIdentity alreadyLinked = pairIfAlreadyLinked(identities);
            if (alreadyLinked != null) return alreadyLinked;
            return mergeAliases(observedJid, identities);
        }), "Identity transaction returned no result");
    }

    /**
     * Stores a name observation using the precedence from contact-identity.ts.
     * This does not create or merge aliases; callers must pass an already resolved chat identity.
     */
    public String observeContactName(String jid, String candidate, ContactNameSource source) {
        Objects.requireNonNull(source, "source");
        String canonical = lookupCanonical(requireJid(jid));
        if (canonical == null) canonical = jid;
        String target = canonical;
        return Objects.requireNonNull(transactions.execute(status -> {
            List<ContactRow> rows = identityStorage.contactsForCanonical(target, true);
            ContactRow current = rows.isEmpty() ? null : rows.getFirst();
            String validCandidate = validName(candidate);
            String selectedName = current == null ? null : validName(current.displayName());
            ContactNameSource selectedSource = selectedName == null ? null : current.nameSource();
            if (validCandidate != null && (selectedName == null || source.rank() >= selectedSource.rank())) {
                selectedName = validCandidate;
                selectedSource = source;
            }

            String lid = current == null ? (isLidJid(target) ? target : null) : current.lidJid();
            String phone = current == null ? pnJid(target) : current.phoneJid();
            String username = current == null ? usernameFromJid(target) : cleanUsername(current.username());
            if (selectedName == null) selectedSource = null;
            identityStorage.upsertContactName(target, lid, phone, username, selectedName, selectedSource);

            String resolved = resolveName(selectedName, username, phone, target);
            identityStorage.updateSnapshotDisplayName(target, resolved.equals(target) ? null : resolved);
            return resolved;
        }), "Contact-name transaction returned no result");
    }

    public String resolveDisplayName(String jid) {
        String validJid = requireJid(jid);
        String canonical = lookupCanonical(validJid);
        if (canonical == null) canonical = validJid;
        List<ContactRow> rows = identityStorage.contactsForCanonical(canonical, false);
        if (rows.isEmpty()) return fallbackName(canonical);
        ContactRow contact = rows.getFirst();
        return resolveName(validName(contact.displayName()), cleanUsername(contact.username()), contact.phoneJid(), canonical);
    }

    private CanonicalIdentity observeSingle(String observedJid) {
        identityStorage.observeAliasIfAbsent(observedJid);
        String canonical = lookupCanonical(observedJid);
        if (canonical == null) canonical = observedJid;
        return identityForCanonical(canonical);
    }

    private CanonicalIdentity mergeAliases(String observedJid, Set<String> identities) {
        // Serialize explicit LID/PN merges under the original advisory and table locks.
        identityStorage.lockAliasMerge();
        CanonicalIdentity alreadyLinked = pairIfAlreadyLinked(identities);
        if (alreadyLinked != null) return alreadyLinked;
        List<String> ordered = new ArrayList<>(new TreeSet<>(identities));
        List<String> canonicalRows = identityStorage.canonicalsForAliases(ordered);
        Set<String> oldCanonicals = new TreeSet<>(canonicalRows);
        String observedCanonical = lookupCanonical(observedJid);
        if (observedCanonical != null) oldCanonicals.add(observedCanonical);
        String canonical = observedCanonical != null ? observedCanonical
                : canonicalRows.isEmpty() ? observedJid : canonicalRows.getFirst();

        Set<String> aliases = new TreeSet<>(ordered);
        if (!oldCanonicals.isEmpty()) {
            aliases.addAll(identityStorage.aliasesForCanonicals(oldCanonicals));
        }
        aliases.addAll(oldCanonicals);

        mergeContactNames(canonical, aliases);
        mergeChatSnapshotsAndPending(canonical, aliases);
        identityStorage.mergeProcessedMessageIdentities(canonical, aliases);
        identityStorage.mergeShadowIdentities(canonical, aliases);
        if (!oldCanonicals.isEmpty()) {
            identityStorage.rekeyCanonicals(canonical, oldCanonicals);
        }
        for (String alias : aliases) {
            identityStorage.upsertAlias(alias, canonical);
        }
        return identityForCanonical(canonical);
    }

    private CanonicalIdentity pairIfAlreadyLinked(Set<String> identities) {
        List<String> ordered = new ArrayList<>(new TreeSet<>(identities));
        List<AliasRow> rows = identityStorage.aliasRows(ordered, false);
        if (rows.size() != ordered.size() || rows.stream().map(AliasRow::canonicalIdentity).distinct().count() != 1) return null;
        List<AliasRow> locked = identityStorage.aliasRows(ordered, true);
        if (locked.size() != ordered.size() || locked.stream().map(AliasRow::canonicalIdentity).distinct().count() != 1) return null;
        return identityForCanonical(locked.getFirst().canonicalIdentity());
    }

    private void mergeContactNames(String canonical, Set<String> aliases) {
        List<String> candidates = new ArrayList<>(aliases);
        candidates.add(canonical);
        List<ContactRow> rows = identityStorage.contactsForCanonicals(candidates);

        ContactRow selected = rows.stream().filter(row -> validName(row.displayName()) != null)
                .max(Comparator.comparingInt(row -> row.nameSource() == null ? 0 : row.nameSource().rank()))
                .orElse(null);
        String displayName = selected == null ? null : validName(selected.displayName());
        ContactNameSource nameSource = selected == null ? null : selected.nameSource();
        String lid = first(aliases, IdentityService::isLidJid);
        String phone = first(aliases, value -> pnJid(value) != null);
        if (phone != null) phone = pnJid(phone);
        String username = rows.stream().map(ContactRow::username).map(IdentityService::cleanUsername)
                .filter(Objects::nonNull).findFirst().orElseGet(() -> first(aliases, value -> usernameFromJid(value) != null));

        identityStorage.replaceContacts(candidates, canonical, lid, phone, username, displayName, nameSource);

    }

    private void mergeChatSnapshotsAndPending(String canonical, Set<String> aliases) {
        List<String> keys = new ArrayList<>(aliases);
        keys.add(canonical);
        keys = keys.stream().distinct().sorted().toList();
        for (String key : keys) lockSnapshot(key);
        List<ChatSnapshotRow> snapshots = identityStorage.snapshotsForUpdate(keys);
        List<PendingRow> pending = identityStorage.pendingForUpdate(keys);

        if (!snapshots.isEmpty()) {
            ChatSnapshotRow latest = snapshots.stream().max(Comparator.comparing(ChatSnapshotRow::snapshotAt)
                    .thenComparing(row -> row.chatJid().equals(canonical) ? 1 : 0)).orElseThrow();
            Instant lastReadAt = snapshots.stream().map(ChatSnapshotRow::lastReadAt).filter(Objects::nonNull)
                    .max(Instant::compareTo).orElse(null);
            List<ContactRow> names = identityStorage.contactsForCanonical(canonical, false);
            String displayName = names.isEmpty() || validName(names.getFirst().displayName()) == null
                    ? validName(latest.displayName()) : names.getFirst().displayName();
            identityStorage.replaceSnapshots(keys, canonical, latest.archived(), latest.snapshotAt(), displayName,
                    snapshots.stream().anyMatch(ChatSnapshotRow::group), latest.synchronizedSnapshot(), lastReadAt);
        }

        if (!pending.isEmpty()) {
            identityStorage.deletePending(keys);
            ChatSnapshotRow latest = snapshots.stream().max(Comparator.comparing(ChatSnapshotRow::snapshotAt)
                    .thenComparing(row -> row.chatJid().equals(canonical) ? 1 : 0)).orElse(null);
            if (latest == null || latest.archived() == null || !latest.synchronizedSnapshot()) {
                pending.forEach(row -> identityStorage.quarantine("PENDING_NOTIFICATION", null,
                        "alias_merge_state_unknown", Instant.now()));
            } else if (Boolean.TRUE.equals(latest.archived())) {
                Instant lastReadAt = snapshots.stream().map(ChatSnapshotRow::lastReadAt).filter(Objects::nonNull)
                        .max(Instant::compareTo).orElse(null);
                List<PendingRow> unread = new ArrayList<>();
                for (PendingRow row : pending) {
                    Map<String, Object> payload = readJson(row.payload());
                    Instant firstMessageAt = strictTimestamp(payload.get("firstMessageAt"));
                    Instant lastMessageAt = strictTimestamp(payload.get("lastMessageAt"));
                    if (firstMessageAt == null || lastMessageAt == null || firstMessageAt.isAfter(lastMessageAt)) {
                        identityStorage.quarantine("PENDING_NOTIFICATION", null,
                                "alias_merge_invalid_pending_timestamp", Instant.now());
                    } else if (lastReadAt != null && !firstMessageAt.isAfter(lastReadAt)) {
                        // Aggregate rows do not retain per-message timestamps; discard a row if READ overlaps it.
                        identityStorage.quarantine("PENDING_NOTIFICATION", null,
                                "alias_merge_read_covered", Instant.now());
                    } else {
                        unread.add(row);
                    }
                }
                if (!unread.isEmpty()) {
                    PendingMerge merged = combinePending(unread);
                    identityStorage.insertPending(canonical, writeJson(merged.payload()),
                            merged.dueAt(), merged.updatedAt(), merged.expiresAt());
                }
            }
        }
    }

    private PendingMerge combinePending(List<PendingRow> rows) {
        int messageCount = 0;
        Instant first = null;
        Instant last = null;
        Instant dueAt = null;
        Instant updatedAt = null;
        PendingRow newestRow = null;
        Map<String, Object> newestPayload = Map.of();
        for (PendingRow row : rows) {
            Map<String, Object> payload = readJson(row.payload());
            Instant rowFirst = timestamp(payload.get("firstMessageAt"), row.updatedAt());
            Instant rowLast = timestamp(payload.get("lastMessageAt"), row.updatedAt());
            messageCount += integer(payload.get("messageCount"));
            first = first == null || rowFirst.isBefore(first) ? rowFirst : first;
            dueAt = dueAt == null || row.dueAt().isBefore(dueAt) ? row.dueAt() : dueAt;
            updatedAt = updatedAt == null || row.updatedAt().isAfter(updatedAt) ? row.updatedAt() : updatedAt;
            if (newestRow == null || rowLast.isAfter(timestamp(readJson(newestRow.payload()).get("lastMessageAt"), newestRow.updatedAt()))) {
                last = rowLast;
                newestRow = row;
                newestPayload = payload;
            }
        }
        Map<String, Object> merged = new LinkedHashMap<>(newestPayload);
        merged.put("messageCount", messageCount);
        merged.put("firstMessageAt", first.toString());
        merged.put("lastMessageAt", last.toString());
        Instant expiresAt = rows.stream().map(PendingRow::expiresAt).max(Instant::compareTo).orElseThrow();
        Instant previewExpiresAt = timestamp(newestPayload.get("previewExpiresAt"), newestRow.expiresAt());
        Instant maxPreviewExpiry = newestRow.updatedAt().plusSeconds(7 * 86_400L);
        if (previewExpiresAt.isAfter(maxPreviewExpiry)) previewExpiresAt = maxPreviewExpiry;
        merged.put("previewExpiresAt", previewExpiresAt.toString());
        return new PendingMerge(merged, dueAt, updatedAt, expiresAt);
    }

    private CanonicalIdentity identityForCanonical(String canonical) {
        return new CanonicalIdentity(canonical, Set.copyOf(identityStorage.aliasesFor(canonical)));
    }

    private String lookupCanonical(String alias) {
        return identityStorage.lookupCanonical(alias);
    }

    private void lockSnapshot(String jid) {
        identityStorage.lockSnapshot(jid);
    }

    private static String first(Set<String> values, java.util.function.Predicate<String> predicate) {
        return values.stream().filter(predicate).findFirst().orElse(null);
    }

    private static String pnJid(String jid) {
        if (jid == null) return null;
        var match = PHONE_JID.matcher(jid);
        return match.matches() ? match.group() : null;
    }

    private static String phoneNumber(String jid) {
        if (jid == null) return null;
        var match = PHONE_JID.matcher(jid);
        return match.matches() ? match.group(1) : null;
    }

    private static boolean isLidJid(String jid) { return jid != null && LID_JID.matcher(jid).matches(); }

    private static String cleanUsername(String value) {
        if (value == null) return null;
        String username = value.trim().replaceAll("^@", "").replaceAll("@+$", "");
        return USERNAME.matcher(username).matches() ? username : null;
    }

    private static String usernameFromJid(String jid) {
        return jid != null && jid.startsWith("@") ? cleanUsername(jid) : null;
    }

    private static String validName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        return trimmed.isEmpty() || TECHNICAL_NAME.matcher(trimmed).matches() ? null : trimmed;
    }

    private static String resolveName(String displayName, String username, String phoneJid, String fallbackJid) {
        String valid = validName(displayName);
        if (valid != null) return valid;
        String clean = cleanUsername(username);
        if (clean != null) return clean;
        String phone = phoneNumber(phoneJid);
        if (phone != null) return "+" + phone;
        return fallbackJid;
    }

    private static String fallbackName(String jid) {
        return resolveName(null, usernameFromJid(jid), jid, jid);
    }

    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try { return Integer.parseInt(string); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private static Instant timestamp(Object value, Instant fallback) {
        if (value instanceof String string) {
            try { return Instant.parse(string); }
            catch (RuntimeException ignored) { return fallback; }
        }
        return fallback;
    }

    private static Instant strictTimestamp(Object value) {
        if (!(value instanceof String string)) return null;
        try { return Instant.parse(string); }
        catch (RuntimeException ignored) { return null; }
    }

    private static Map<String, Object> readJson(String value) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(value, Map.class);
            return parsed;
        }
        catch (JacksonException ex) { return Map.of(); }
    }

    private static String writeJson(Map<String, Object> value) {
        try { return JSON.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalArgumentException("Could not encode merged pending notification", ex); }
    }

    private static String requireJid(String jid) {
        if (jid == null || jid.isBlank() || jid.length() > 255) {
            throw new IllegalArgumentException("JID must be non-blank and at most 255 characters");
        }
        return jid;
    }

    private record PendingMerge(Map<String, Object> payload, Instant dueAt, Instant updatedAt, Instant expiresAt) { }
}
