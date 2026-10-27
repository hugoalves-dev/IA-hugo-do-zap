package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
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

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public IdentityService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
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
            List<ContactRow> rows = jdbc.query("""
                    select canonical_jid, lid_jid, phone_jid, username, display_name, display_name_source, updated_at
                    from contact_identity where canonical_jid=? for update
                    """, (rs, row) -> new ContactRow(rs.getString("canonical_jid"), rs.getString("lid_jid"),
                    rs.getString("phone_jid"), rs.getString("username"), rs.getString("display_name"),
                    source(rs.getString("display_name_source")), rs.getTimestamp("updated_at").toInstant()), target);
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
            jdbc.update("""
                    insert into contact_identity(canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at)
                    values (?,?,?,?,?,?,?)
                    on conflict (canonical_jid) do update set
                        lid_jid=coalesce(excluded.lid_jid,contact_identity.lid_jid),
                        phone_jid=coalesce(excluded.phone_jid,contact_identity.phone_jid),
                        username=coalesce(excluded.username,contact_identity.username),
                        display_name=excluded.display_name,
                        display_name_source=excluded.display_name_source,
                        updated_at=excluded.updated_at
                    """, target, lid, phone, username, selectedName, selectedSource == null ? null : selectedSource.name(), Timestamp.from(Instant.now()));

            String resolved = resolveName(selectedName, username, phone, target);
            jdbc.update("update chat_snapshot set display_name=? where chat_jid=?", resolved.equals(target) ? null : resolved, target);
            return resolved;
        }), "Contact-name transaction returned no result");
    }

    public String resolveDisplayName(String jid) {
        String validJid = requireJid(jid);
        String canonical = lookupCanonical(validJid);
        if (canonical == null) canonical = validJid;
        List<ContactRow> rows = jdbc.query("""
                select canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at
                from contact_identity where canonical_jid=?
                """, (rs, row) -> new ContactRow(rs.getString("canonical_jid"), rs.getString("lid_jid"),
                rs.getString("phone_jid"), rs.getString("username"), rs.getString("display_name"),
                source(rs.getString("display_name_source")), rs.getTimestamp("updated_at").toInstant()), canonical);
        if (rows.isEmpty()) return fallbackName(canonical);
        ContactRow contact = rows.getFirst();
        return resolveName(validName(contact.displayName()), cleanUsername(contact.username()), contact.phoneJid(), canonical);
    }

    private CanonicalIdentity observeSingle(String observedJid) {
        jdbc.update("""
                insert into identity_aliases(alias, canonical_identity) values (?, ?)
                on conflict (alias) do nothing
                """, observedJid, observedJid);
        String canonical = lookupCanonical(observedJid);
        if (canonical == null) canonical = observedJid;
        return identityForCanonical(canonical);
    }

    private CanonicalIdentity mergeAliases(String observedJid, Set<String> identities) {
        // Explicit LID/PN pair merges are rare; keep the table lock off normal observations.
        // SHARE ROW EXCLUSIVE permits readers while serializing alias-set rewrites.
        jdbc.execute("lock table identity_aliases in share row exclusive mode");
        CanonicalIdentity alreadyLinked = pairIfAlreadyLinked(identities);
        if (alreadyLinked != null) return alreadyLinked;
        List<String> ordered = new ArrayList<>(new TreeSet<>(identities));
        String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        List<String> canonicalRows = jdbc.query(
                "select canonical_identity from identity_aliases where alias in (" + placeholders + ") order by alias",
                (rs, row) -> rs.getString(1), ordered.toArray());
        Set<String> oldCanonicals = new TreeSet<>(canonicalRows);
        String observedCanonical = lookupCanonical(observedJid);
        if (observedCanonical != null) oldCanonicals.add(observedCanonical);
        String canonical = observedCanonical != null ? observedCanonical
                : canonicalRows.isEmpty() ? observedJid : canonicalRows.getFirst();

        Set<String> aliases = new TreeSet<>(ordered);
        if (!oldCanonicals.isEmpty()) {
            String canonicalPlaceholders = String.join(",", java.util.Collections.nCopies(oldCanonicals.size(), "?"));
            aliases.addAll(jdbc.query("select alias from identity_aliases where canonical_identity in (" + canonicalPlaceholders + ") order by alias",
                    (rs, row) -> rs.getString(1), oldCanonicals.toArray()));
        }
        aliases.addAll(oldCanonicals);

        mergeContactNames(canonical, aliases);
        mergeChatSnapshotsAndPending(canonical, aliases);
        if (!oldCanonicals.isEmpty()) {
            String oldPlaceholders = String.join(",", java.util.Collections.nCopies(oldCanonicals.size(), "?"));
            jdbc.update("update identity_aliases set canonical_identity=? where canonical_identity in (" + oldPlaceholders + ")",
                    prepend(canonical, oldCanonicals));
        }
        for (String alias : aliases) {
            jdbc.update("""
                    insert into identity_aliases(alias,canonical_identity) values (?,?)
                    on conflict (alias) do update set canonical_identity=excluded.canonical_identity
                    """, alias, canonical);
        }
        return identityForCanonical(canonical);
    }

    private CanonicalIdentity pairIfAlreadyLinked(Set<String> identities) {
        List<String> ordered = new ArrayList<>(new TreeSet<>(identities));
        String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        List<AliasRow> rows = jdbc.query("""
                select alias,canonical_identity from identity_aliases
                where alias in (""" + placeholders + ") order by alias",
                (rs, row) -> new AliasRow(rs.getString("alias"), rs.getString("canonical_identity")), ordered.toArray());
        if (rows.size() != ordered.size() || rows.stream().map(AliasRow::canonicalIdentity).distinct().count() != 1) return null;
        List<AliasRow> locked = jdbc.query("""
                select alias,canonical_identity from identity_aliases
                where alias in (""" + placeholders + ") order by alias for share",
                (rs, row) -> new AliasRow(rs.getString("alias"), rs.getString("canonical_identity")), ordered.toArray());
        if (locked.size() != ordered.size() || locked.stream().map(AliasRow::canonicalIdentity).distinct().count() != 1) return null;
        return identityForCanonical(locked.getFirst().canonicalIdentity());
    }

    private void mergeContactNames(String canonical, Set<String> aliases) {
        List<String> candidates = new ArrayList<>(aliases);
        candidates.add(canonical);
        String placeholders = String.join(",", java.util.Collections.nCopies(candidates.size(), "?"));
        List<ContactRow> rows = jdbc.query("""
                select canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at
                from contact_identity where canonical_jid in (""" + placeholders + ") order by canonical_jid for update",
                (rs, row) -> new ContactRow(rs.getString("canonical_jid"), rs.getString("lid_jid"),
                        rs.getString("phone_jid"), rs.getString("username"), rs.getString("display_name"),
                        source(rs.getString("display_name_source")), rs.getTimestamp("updated_at").toInstant()), candidates.toArray());

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

        jdbc.update("delete from contact_identity where canonical_jid in (" + placeholders + ")", candidates.toArray());
        jdbc.update("""
                insert into contact_identity(canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at)
                values (?,?,?,?,?,?,?)
                """, canonical, lid, phone, username, displayName, nameSource == null ? null : nameSource.name(), Timestamp.from(Instant.now()));
    }

    private void mergeChatSnapshotsAndPending(String canonical, Set<String> aliases) {
        List<String> keys = new ArrayList<>(aliases);
        keys.add(canonical);
        keys = keys.stream().distinct().sorted().toList();
        for (String key : keys) lockSnapshot(key);
        String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
        List<ChatSnapshotRow> snapshots = jdbc.query("""
                select chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot,last_read_at
                from chat_snapshot where chat_jid in (""" + placeholders + ") order by chat_jid for update",
                (rs, row) -> new ChatSnapshotRow(rs.getString("chat_jid"), (Boolean) rs.getObject("archived"),
                        rs.getTimestamp("snapshot_at").toInstant(), rs.getString("display_name"), rs.getBoolean("is_group"),
                        rs.getBoolean("synchronized_snapshot"), rs.getTimestamp("last_read_at") == null ? null : rs.getTimestamp("last_read_at").toInstant()),
                keys.toArray());
        List<PendingRow> pending = jdbc.query("""
                select chat_jid,payload::text as payload,due_at,updated_at,expires_at
                from pending_notification where chat_jid in (""" + placeholders + ") order by chat_jid for update",
                (rs, row) -> new PendingRow(rs.getString("chat_jid"), rs.getString("payload"), rs.getTimestamp("due_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("expires_at").toInstant()), keys.toArray());

        if (!snapshots.isEmpty()) {
            ChatSnapshotRow latest = snapshots.stream().max(Comparator.comparing(ChatSnapshotRow::snapshotAt)
                    .thenComparing(row -> row.chatJid().equals(canonical) ? 1 : 0)).orElseThrow();
            Instant lastReadAt = snapshots.stream().map(ChatSnapshotRow::lastReadAt).filter(Objects::nonNull)
                    .max(Instant::compareTo).orElse(null);
            List<String> nameParams = new ArrayList<>();
            nameParams.add(canonical);
            List<ContactRow> names = jdbc.query("select canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at from contact_identity where canonical_jid=?",
                    (rs, row) -> new ContactRow(rs.getString("canonical_jid"), rs.getString("lid_jid"), rs.getString("phone_jid"),
                            rs.getString("username"), rs.getString("display_name"), source(rs.getString("display_name_source")),
                            rs.getTimestamp("updated_at").toInstant()), nameParams.toArray());
            String displayName = names.isEmpty() || validName(names.getFirst().displayName()) == null
                    ? validName(latest.displayName()) : names.getFirst().displayName();
            jdbc.update("delete from chat_snapshot where chat_jid in (" + placeholders + ")", keys.toArray());
            jdbc.update("""
                    insert into chat_snapshot(chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot,last_read_at)
                    values (?,?,?,?,?,?,?)
                    """, canonical, latest.archived(), Timestamp.from(latest.snapshotAt()), displayName,
                    snapshots.stream().anyMatch(ChatSnapshotRow::group), latest.synchronizedSnapshot(),
                    lastReadAt == null ? null : Timestamp.from(lastReadAt));
        }

        if (!pending.isEmpty()) {
            jdbc.update("delete from pending_notification where chat_jid in (" + placeholders + ")", keys.toArray());
            if (snapshots.stream().max(Comparator.comparing(ChatSnapshotRow::snapshotAt)).map(ChatSnapshotRow::archived)
                    .filter(Boolean.FALSE::equals).isEmpty()) {
                PendingMerge merged = combinePending(pending);
                jdbc.update("""
                        insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at)
                        values (?,?::jsonb,?,?,?)
                        """, canonical, writeJson(merged.payload()), Timestamp.from(merged.dueAt()),
                        Timestamp.from(merged.updatedAt()), Timestamp.from(merged.expiresAt()));
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
        return new PendingMerge(merged, dueAt, updatedAt, newestRow.expiresAt());
    }

    private CanonicalIdentity identityForCanonical(String canonical) {
        List<String> aliases = jdbc.query("select alias from identity_aliases where canonical_identity=? order by alias",
                (rs, row) -> rs.getString(1), canonical);
        return new CanonicalIdentity(canonical, Set.copyOf(aliases));
    }

    private String lookupCanonical(String alias) {
        List<String> values = jdbc.query("select canonical_identity from identity_aliases where alias=?",
                (rs, row) -> rs.getString(1), alias);
        return values.isEmpty() ? null : values.getFirst();
    }

    private void lockSnapshot(String jid) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, jid);
    }

    private static List<Object> prepend(Object head, Set<String> rest) {
        List<Object> values = new ArrayList<>();
        values.add(head);
        values.addAll(rest);
        return values;
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

    private static ContactNameSource source(String value) {
        if (value == null) return null;
        try { return ContactNameSource.valueOf(value); }
        catch (IllegalArgumentException ex) { return ContactNameSource.LEGACY; }
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

    private record ContactRow(String canonicalJid, String lidJid, String phoneJid, String username,
                              String displayName, ContactNameSource nameSource, Instant updatedAt) { }
    private record AliasRow(String alias, String canonicalIdentity) { }
    private record ChatSnapshotRow(String chatJid, Boolean archived, Instant snapshotAt, String displayName,
                                   boolean group, boolean synchronizedSnapshot, Instant lastReadAt) { }
    private record PendingRow(String chatJid, String payload, Instant dueAt, Instant updatedAt, Instant expiresAt) { }
    private record PendingMerge(Map<String, Object> payload, Instant dueAt, Instant updatedAt, Instant expiresAt) { }
}
