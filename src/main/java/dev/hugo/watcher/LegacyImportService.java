package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import dev.hugo.watcher.LegacySqliteSnapshotReader.SourceData;
import dev.hugo.watcher.LegacySqliteSnapshotReader.SourceIdentity;
import dev.hugo.watcher.LegacySqliteSnapshotReader.SourcePending;
import dev.hugo.watcher.LegacyImportPersistenceRepository.ExistingPending;
import dev.hugo.watcher.LegacyImportPersistenceRepository.IdentityAnchors;

/** Imports identities and pending metadata from a user-provided SQLite copy, never its archive state. */
@Service
public class LegacyImportService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long MAX_RETENTION_SECONDS = 7 * 86_400L;

    private final LegacyImportPersistenceRepository persistence;
    private final LegacySqliteSnapshotReader reader;
    private final TransactionTemplate transactions;

    /** Production: SQLite snapshots and PostgreSQL writes are separate collaborators. */
    public LegacyImportService(LegacySqliteSnapshotReader reader,
                               LegacyImportPersistenceRepository persistence,
                               PlatformTransactionManager manager) {
        this.reader = reader;
        this.persistence = persistence;
        this.transactions = new TransactionTemplate(manager);
    }

    /** Compatibility adapter for standalone migration tests. */
    public LegacyImportService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(new LegacySqliteSnapshotReader(), new LegacyImportPersistenceRepository(jdbc), manager);
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
        try {
            source = reader.read(snapshot);
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
            Map<String, String> sourceCanonical = new LinkedHashMap<>();
            for (SourceIdentity identity : source.identities()) for (String alias : identity.aliases()) {
                String prior = sourceCanonical.putIfAbsent(alias, identity.canonicalJid());
                if (prior != null && !prior.equals(identity.canonicalJid())) {
                    conflicts.add(conflict("identity", alias, "source_alias_collision"));
                    blockedAliases.add(alias);
                }
            }

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

            Map<String, List<PendingValue>> grouped = new LinkedHashMap<>();
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
                String sourceKey = sourceCanonical.getOrDefault(item.jid(), item.jid());
                List<String> targetCanonical = persistence.canonicalValues(item.jid());
                if (!targetCanonical.isEmpty() && sourceCanonical.containsKey(item.jid())
                        && !targetCanonical.getFirst().equals(sourceKey)) {
                    conflicts.add(conflict("pending", item.jid(), "source_target_identity_mismatch"));
                    continue;
                }
                String canonical = targetCanonical.isEmpty() ? sourceKey : targetCanonical.getFirst();
                grouped.computeIfAbsent(canonical, ignored -> new ArrayList<>()).add(pendingValue(item, expiresAt));
            }
            for (Map.Entry<String, List<PendingValue>> group : grouped.entrySet()) {
                String jid = group.getKey();
                PendingValue value;
                try { value = mergePending(group.getValue()); }
                catch (RuntimeException ex) { conflicts.add(conflict("pending", jid, "pending_merge_conflict")); continue; }
                List<ExistingPending> existing = persistence.existingPending(jid);
                if (!existing.isEmpty()) {
                    if (samePending(existing.getFirst(), value)) unchanged++;
                    else conflicts.add(conflict("pending", jid, "target_pending_differs"));
                    continue;
                }
                plannedPendings++;
                if (mode == ImportMode.APPLY) {
                    persistence.insertPending(jid, writeJson(value.payload()), value.dueAt(),
                            value.updatedAt(), value.expiresAt());
                    if (persistence.matchingPendingCount(jid, writeJson(value.payload()), value.dueAt(),
                            value.updatedAt(), value.expiresAt()) != 1) {
                        conflicts.add(conflict("pending", jid, "target_pending_changed_during_import"));
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

    private String identityConflict(SourceIdentity identity) {
        for (String alias : identity.aliases()) {
            List<String> values = persistence.canonicalValues(alias);
            if (!values.isEmpty() && !identity.canonicalJid().equals(values.getFirst()))
                return "alias_maps_to_another_identity";
        }
        List<IdentityAnchors> values = persistence.contactAnchors(identity.canonicalJid());
        if (!values.isEmpty() && ((values.getFirst().lidJid() != null && identity.lidJid() != null
                && !values.getFirst().lidJid().equals(identity.lidJid()))
                || (values.getFirst().phoneJid() != null && identity.phoneJid() != null
                && !values.getFirst().phoneJid().equals(identity.phoneJid()))))
            return "identity_anchor_differs";
        if (!persistence.otherAnchorOwners(identity.canonicalJid(), identity.lidJid(), identity.phoneJid()).isEmpty())
            return "identity_anchor_maps_to_another_identity";
        return null;
    }

    private boolean identityAliasConflict(SourceIdentity identity) {
        for (String alias : identity.aliases()) {
            List<String> values = persistence.canonicalValues(alias);
            if (values.isEmpty() || !identity.canonicalJid().equals(values.getFirst())) return true;
        }
        return false;
    }

    private boolean contactExists(String canonical) {
        return persistence.contactExists(canonical);
    }

    private void upsertContact(SourceIdentity identity) {
        persistence.upsertContact(identity.canonicalJid(), identity.lidJid(), identity.phoneJid(),
                identity.username(), identity.displayName(), nameSource(identity.displayNameSource()),
                parseInstant(identity.updatedAt()));
    }

    private void upsertAlias(String alias, String canonical) {
        persistence.upsertAlias(alias, canonical);
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

    private static PendingValue mergePending(List<PendingValue> values) {
        PendingValue newest = values.getFirst();
        Instant first = null, last = null, due = null, updated = null, expiry = null;
        int count = 0;
        for (PendingValue value : values) {
            Map<String, Object> p = value.payload();
            if (!Objects.equals(p.get("isGroup"), newest.payload().get("isGroup"))) throw new IllegalArgumentException();
            if (!Objects.equals(p.get("notificationPolicy"), newest.payload().get("notificationPolicy"))) throw new IllegalArgumentException();
            count = Math.addExact(count, (Integer) p.get("messageCount"));
            Instant f = parseInstant((String) p.get("firstMessageAt")), l = parseInstant((String) p.get("lastMessageAt"));
            if (first == null || f.isBefore(first)) first = f;
            if (last == null || l.isAfter(last)) { last = l; newest = value; }
            if (due == null || value.dueAt().isBefore(due)) due = value.dueAt();
            if (updated == null || value.updatedAt().isAfter(updated)) updated = value.updatedAt();
            if (expiry == null || value.expiresAt().isAfter(expiry)) expiry = value.expiresAt();
        }
        Map<String,Object> payload = new LinkedHashMap<>(newest.payload());
        payload.put("messageCount", count); payload.put("firstMessageAt", first.toString()); payload.put("lastMessageAt", last.toString());
        return new PendingValue(payload, due, updated, expiry);
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

    private record PendingValue(Map<String, Object> payload, Instant dueAt, Instant updatedAt, Instant expiresAt) { }
}
