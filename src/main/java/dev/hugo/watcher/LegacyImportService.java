package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Imports identities and pending metadata from a user-provided SQLite copy, never its archive state. */
@Service
public class LegacyImportService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long MAX_RETENTION_SECONDS = 7 * 86_400L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public LegacyImportService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    /**
     * Reads a stable legacy snapshot in SQLite read-only/query-only mode. The source must be a copy,
     * not the live legacy database. No legacy archived flag is read or copied.
     */
    public ImportReport importSnapshot(Path snapshot, ImportMode mode) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(mode, "mode");
        if (!Files.isRegularFile(snapshot)) throw new IllegalArgumentException("SQLite snapshot must be a regular file");
        SourceData source;
        try (Connection connection = openReadOnly(snapshot)) {
            source = readSource(connection);
        } catch (SQLException ex) {
            throw new IllegalArgumentException("SQLite snapshot could not be read with the supported legacy schema", ex);
        }

        Instant importedAt = Instant.now();
        return Objects.requireNonNull(transactions.execute(status -> {
            List<ImportConflict> conflicts = new ArrayList<>();
            Set<String> blockedAliases = new LinkedHashSet<>();
            int identities = 0;
            int pending = 0;
            int plannedIdentities = 0;
            int plannedPendings = 0;
            int unchanged = 0;
            int expired = 0;

            for (SourceIdentity identity : source.identities()) {
                String invalid = invalidIdentity(identity);
                if (invalid != null) {
                    conflicts.add(conflict("identity", identity.canonicalJid(), invalid));
                    blockedAliases.addAll(identity.aliases());
                    continue;
                }
                String conflict = identityConflict(identity);
                if (conflict != null) {
                    conflicts.add(conflict("identity", identity.canonicalJid(), conflict));
                    blockedAliases.addAll(identity.aliases());
                    continue;
                }
                boolean existed = contactExists(identity.canonicalJid());
                if (mode == ImportMode.APPLY) {
                    upsertContact(identity);
                    for (String alias : identity.aliases()) upsertAlias(alias, identity.canonicalJid());
                    if (identityAliasConflict(identity)) {
                        conflicts.add(conflict("identity", identity.canonicalJid(), "alias_changed_during_import"));
                        blockedAliases.addAll(identity.aliases());
                        continue;
                    }
                }
                if (existed) unchanged++;
                else {
                    plannedIdentities++;
                    if (mode == ImportMode.APPLY) identities++;
                }
            }

            for (SourcePending item : source.pending()) {
                String invalid = invalidPending(item);
                if (invalid != null) {
                    conflicts.add(conflict("pending", item.jid(), invalid));
                    continue;
                }
                if (blockedAliases.contains(item.jid())) continue;
                Instant expiresAt = parseInstant(item.lastMessageAt()).plusSeconds(MAX_RETENTION_SECONDS);
                if (!expiresAt.isAfter(importedAt)) {
                    expired++;
                    continue;
                }
                PendingValue value = pendingValue(item, expiresAt);
                List<ExistingPending> existing = jdbc.query("""
                        select payload::text as payload,due_at,updated_at,expires_at
                        from pending_notification where chat_jid=?
                        """, (rs, row) -> new ExistingPending(rs.getString("payload"), rs.getTimestamp("due_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(), rs.getTimestamp("expires_at").toInstant()), item.jid());
                if (!existing.isEmpty()) {
                    if (samePending(existing.getFirst(), value)) unchanged++;
                    else conflicts.add(conflict("pending", item.jid(), "target_pending_differs"));
                    continue;
                }
                plannedPendings++;
                if (mode == ImportMode.APPLY) {
                    jdbc.update("""
                            insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at)
                            values (?,?::jsonb,?,?,?)
                            on conflict (chat_jid) do nothing
                            """, item.jid(), writeJson(value.payload()), Timestamp.from(value.dueAt()),
                            Timestamp.from(value.updatedAt()), Timestamp.from(value.expiresAt()));
                    if (jdbc.queryForObject("select count(*) from pending_notification where chat_jid=? and payload=?::jsonb and due_at=? and updated_at=? and expires_at=?",
                            Integer.class, item.jid(), writeJson(value.payload()), Timestamp.from(value.dueAt()), Timestamp.from(value.updatedAt()),
                            Timestamp.from(value.expiresAt())) != 1) {
                        conflicts.add(conflict("pending", item.jid(), "target_pending_changed_during_import"));
                    } else pending++;
                }
            }

            if (!conflicts.isEmpty() && mode == ImportMode.APPLY) {
                status.setRollbackOnly();
                identities = 0;
                pending = 0;
            }
            return new ImportReport(identities, pending, plannedIdentities, plannedPendings,
                    unchanged, expired, conflicts, mode == ImportMode.DRY_RUN);
        }), "Legacy import transaction returned no result");
    }

    private static Connection openReadOnly(Path snapshot) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot.toAbsolutePath(), config.toProperties());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only=ON");
        }
        return connection;
    }

    private static SourceData readSource(Connection connection) throws SQLException {
        requireColumns(connection, "contact_identity", Set.of("canonical_jid", "lid_jid", "phone_jid", "username", "display_name", "display_name_source", "updated_at"));
        requireColumns(connection, "pending_notification", Set.of("jid", "message_count", "first_message_at", "last_message_at", "last_message_preview"));
        requireColumns(connection, "chat_state", Set.of("jid", "is_group", "notification_policy"));
        List<SourceIdentity> identities = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at
                     from contact_identity order by canonical_jid
                     """)) {
            while (rows.next()) identities.add(new SourceIdentity(rows.getString("canonical_jid"), rows.getString("lid_jid"),
                    rows.getString("phone_jid"), rows.getString("username"), rows.getString("display_name"),
                    rows.getString("display_name_source"), rows.getString("updated_at")));
        }
        List<SourcePending> pending = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select p.jid,p.message_count,p.first_message_at,p.last_message_at,p.last_message_preview,
                            c.is_group,c.notification_policy
                     from pending_notification p left join chat_state c on c.jid=p.jid order by p.jid
                     """)) {
            while (rows.next()) pending.add(new SourcePending(rows.getString("jid"), rows.getInt("message_count"),
                    rows.getString("first_message_at"), rows.getString("last_message_at"), rows.getString("last_message_preview"),
                    rows.getObject("is_group") == null ? null : rows.getInt("is_group"), rows.getString("notification_policy")));
        }
        return new SourceData(identities, pending);
    }

    private static void requireColumns(Connection connection, String table, Set<String> required) throws SQLException {
        Set<String> actual = new TreeSet<>();
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery("pragma table_info(" + table + ")")) {
            while (rows.next()) actual.add(rows.getString("name"));
        }
        if (!actual.containsAll(required)) throw new SQLException("Unsupported legacy schema in " + table);
    }

    private String identityConflict(SourceIdentity identity) {
        for (String alias : identity.aliases()) {
            List<String> values = jdbc.query("select canonical_identity from identity_aliases where alias=?",
                    (rs, row) -> rs.getString(1), alias);
            if (!values.isEmpty() && !identity.canonicalJid().equals(values.getFirst())) return "alias_maps_to_another_identity";
        }
        List<IdentityAnchors> values = jdbc.query("select lid_jid,phone_jid from contact_identity where canonical_jid=?",
                (rs, row) -> new IdentityAnchors(rs.getString("lid_jid"), rs.getString("phone_jid")), identity.canonicalJid());
        if (!values.isEmpty() && ((values.getFirst().lidJid() != null && identity.lidJid() != null
                && !values.getFirst().lidJid().equals(identity.lidJid()))
                || (values.getFirst().phoneJid() != null && identity.phoneJid() != null
                && !values.getFirst().phoneJid().equals(identity.phoneJid())))) return "identity_anchor_differs";
        List<String> anchorOwners = jdbc.query("""
                select canonical_jid from contact_identity
                where canonical_jid<>? and ((? is not null and lid_jid=?) or (? is not null and phone_jid=?))
                order by canonical_jid
                """, (rs, row) -> rs.getString(1), identity.canonicalJid(), identity.lidJid(), identity.lidJid(),
                identity.phoneJid(), identity.phoneJid());
        if (!anchorOwners.isEmpty()) return "identity_anchor_maps_to_another_identity";
        return null;
    }

    private boolean identityAliasConflict(SourceIdentity identity) {
        for (String alias : identity.aliases()) {
            List<String> values = jdbc.query("select canonical_identity from identity_aliases where alias=?",
                    (rs, row) -> rs.getString(1), alias);
            if (values.isEmpty() || !identity.canonicalJid().equals(values.getFirst())) return true;
        }
        return false;
    }

    private boolean contactExists(String canonical) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from contact_identity where canonical_jid=?)", Boolean.class, canonical));
    }

    private void upsertContact(SourceIdentity identity) {
        jdbc.update("""
                insert into contact_identity(canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at)
                values (?,?,?,?,?,?,?)
                on conflict (canonical_jid) do update set
                    lid_jid=coalesce(contact_identity.lid_jid,excluded.lid_jid),
                    phone_jid=coalesce(contact_identity.phone_jid,excluded.phone_jid),
                    username=coalesce(contact_identity.username,excluded.username),
                    display_name=coalesce(contact_identity.display_name,excluded.display_name),
                    display_name_source=coalesce(contact_identity.display_name_source,excluded.display_name_source),
                    updated_at=greatest(contact_identity.updated_at,excluded.updated_at)
                """, identity.canonicalJid(), identity.lidJid(), identity.phoneJid(), identity.username(),
                identity.displayName(), nameSource(identity.displayNameSource()), Timestamp.from(parseInstant(identity.updatedAt())));
    }

    private void upsertAlias(String alias, String canonical) {
        jdbc.update("""
                insert into identity_aliases(alias,canonical_identity) values (?,?)
                on conflict (alias) do update set canonical_identity=identity_aliases.canonical_identity
                where identity_aliases.canonical_identity=excluded.canonical_identity
                """, alias, canonical);
    }

    private static boolean samePending(ExistingPending existing, PendingValue expected) {
        if (!existing.dueAt().equals(expected.dueAt()) || !existing.updatedAt().equals(expected.updatedAt())
                || !existing.expiresAt().equals(expected.expiresAt())) return false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> actual = JSON.readValue(existing.payload(), Map.class);
            for (Map.Entry<String, Object> field : expected.payload().entrySet()) {
                if (!Objects.equals(String.valueOf(field.getValue()), String.valueOf(actual.get(field.getKey())))) return false;
            }
            return true;
        } catch (JacksonException ex) {
            return false;
        }
    }

    private static PendingValue pendingValue(SourcePending item, Instant expiresAt) {
        Instant first = parseInstant(item.firstMessageAt());
        Instant last = parseInstant(item.lastMessageAt());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageCount", item.messageCount());
        payload.put("firstMessageAt", first.toString());
        payload.put("lastMessageAt", last.toString());
        if (item.preview() != null) {
            payload.put("preview", item.preview());
            payload.put("previewExpiresAt", expiresAt.toString());
        }
        payload.put("isGroup", item.isGroup() == 1);
        payload.put("notificationPolicy", item.notificationPolicy());
        return new PendingValue(payload, last, last, expiresAt);
    }

    private static String invalidIdentity(SourceIdentity identity) {
        if (!validJid(identity.canonicalJid()) || identity.lidJid() != null && !validJid(identity.lidJid())
                || identity.phoneJid() != null && !validJid(identity.phoneJid())) return "invalid_jid";
        if (identity.username() != null && identity.username().length() > 64) return "invalid_username";
        try { parseInstant(identity.updatedAt()); }
        catch (RuntimeException ex) { return "invalid_updated_at"; }
        return null;
    }

    private static String invalidPending(SourcePending item) {
        if (!validJid(item.jid())) return "invalid_jid";
        if (item.messageCount() < 1) return "invalid_message_count";
        if (item.isGroup() == null || item.isGroup() < 0 || item.isGroup() > 1) return "missing_or_invalid_chat_metadata";
        if (!"ANYTIME".equals(item.notificationPolicy()) && !"WORK_ONLY".equals(item.notificationPolicy())) return "invalid_notification_policy";
        try {
            Instant first = parseInstant(item.firstMessageAt());
            Instant last = parseInstant(item.lastMessageAt());
            if (first.isAfter(last) || last.isAfter(Instant.now().plusSeconds(60))) return "invalid_message_timestamps";
        } catch (RuntimeException ex) {
            return "invalid_message_timestamps";
        }
        return null;
    }

    private static boolean validJid(String jid) { return jid != null && !jid.isBlank() && jid.length() <= 255; }

    private static Instant parseInstant(String value) { return Instant.parse(Objects.requireNonNull(value, "timestamp")); }

    private static String nameSource(String value) {
        if (value == null || value.isBlank()) return ContactNameSource.LEGACY.name();
        try { return ContactNameSource.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)).name(); }
        catch (IllegalArgumentException ex) { return ContactNameSource.LEGACY.name(); }
    }

    private static ImportConflict conflict(String entity, String key, String reason) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((entity + "\n" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new ImportConflict(entity, HexFormat.of().formatHex(hash, 0, 6), reason);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String writeJson(Map<String, Object> value) {
        try { return JSON.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalArgumentException("Could not encode imported pending metadata", ex); }
    }

    private record SourceData(List<SourceIdentity> identities, List<SourcePending> pending) { }
    private record SourceIdentity(String canonicalJid, String lidJid, String phoneJid, String username,
                                  String displayName, String displayNameSource, String updatedAt) {
        Set<String> aliases() {
            Set<String> result = new TreeSet<>();
            if (canonicalJid != null) result.add(canonicalJid);
            if (lidJid != null) result.add(lidJid);
            if (phoneJid != null) result.add(phoneJid);
            return result;
        }
    }
    private record SourcePending(String jid, int messageCount, String firstMessageAt, String lastMessageAt,
                                 String preview, Integer isGroup, String notificationPolicy) { }
    private record ExistingPending(String payload, Instant dueAt, Instant updatedAt, Instant expiresAt) { }
    private record IdentityAnchors(String lidJid, String phoneJid) { }
    private record PendingValue(Map<String, Object> payload, Instant dueAt, Instant updatedAt, Instant expiresAt) { }
}
