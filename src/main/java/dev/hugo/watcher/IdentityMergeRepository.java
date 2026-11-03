package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL-native canonical-identity read/rekey operations.
 * Caller holds the identity merge advisory/table locks and the enclosing transaction.
 */
@Repository
public class IdentityMergeRepository {
    private final JdbcTemplate jdbc;

    public IdentityMergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> aliasesFor(String canonical) {
        return jdbc.query("select alias from identity_aliases where canonical_identity=? order by alias",
                (rs, row) -> rs.getString(1), canonical);
    }

    public String lookupCanonical(String alias) {
        List<String> values = jdbc.query("select canonical_identity from identity_aliases where alias=?",
                (rs, row) -> rs.getString(1), alias);
        return values.isEmpty() ? null : values.getFirst();
    }

    public void mergeShadowIdentities(String canonical, Set<String> aliases) {
        // Evidence is the same explicit alternate-JID pair required by the identity merge itself.
        // Never infer identity from names, message content or similar phone numbers.
        for (String alias : aliases) {
            if (canonical.equals(alias)) continue;
            jdbc.update("""
                    insert into chat_automation_authorizations(chat_jid,situation,template_id,template_version,authorized_at)
                    select ?,situation,template_id,template_version,authorized_at from chat_automation_authorizations where chat_jid=?
                    on conflict do nothing
                    """, canonical, alias);
            jdbc.update("delete from chat_automation_authorizations where chat_jid=?", alias);
            jdbc.update("""
                    insert into decision_cooldowns(chat_jid,participant_jid,last_simulation_action_at,last_real_action_at)
                    select case when chat_jid=? then ? else chat_jid end,
                           case when participant_jid=? then ? else participant_jid end,
                           last_simulation_action_at,last_real_action_at
                      from decision_cooldowns where chat_jid=? or participant_jid=?
                    on conflict(chat_jid,participant_jid) do update set
                        last_simulation_action_at=greatest(decision_cooldowns.last_simulation_action_at,excluded.last_simulation_action_at),
                        last_real_action_at=greatest(decision_cooldowns.last_real_action_at,excluded.last_real_action_at)
                    """, alias, canonical, alias, canonical, alias, alias);
            jdbc.update("delete from decision_cooldowns where chat_jid=? or participant_jid=?", alias, alias);
            // Keep both batches for audit. Close OPEN heads before rekeying to avoid unique-head conflicts.
            jdbc.update("update simulation_batches set status='READY' where status='OPEN' and (chat_jid in (?,?) or participant_jid in (?,?))",
                    alias, canonical, alias, canonical);
            jdbc.update("update simulation_batches set chat_jid=? where chat_jid=?", canonical, alias);
            jdbc.update("update simulation_batches set participant_jid=? where participant_jid=?", canonical, alias);
            // Deduplicate only the same upstream message within the same canonical chat and sender.
            jdbc.update("""
                    delete from simulation_batch_messages old using simulation_batch_messages kept
                     where (old.batch_id,old.external_id)<>(kept.batch_id,kept.external_id)
                       and old.external_id=kept.external_id
                       and (case when old.chat_jid=? then ? else old.chat_jid end)=(case when kept.chat_jid=? then ? else kept.chat_jid end)
                       and (case when old.participant_jid=? then ? else old.participant_jid end)=(case when kept.participant_jid=? then ? else kept.participant_jid end)
                       and old.batch_id>kept.batch_id
                    """, alias, canonical, alias, canonical, alias, canonical, alias, canonical);
            jdbc.update("update simulation_batch_messages set chat_jid=? where chat_jid=?", canonical, alias);
            jdbc.update("update simulation_batch_messages set participant_jid=? where participant_jid=?", canonical, alias);
            jdbc.update("""
                    update processed_message set shadow_payload=jsonb_set(shadow_payload,'{chatJid}',to_jsonb(canonical_chat_jid))
                    where shadow_payload is not null and canonical_chat_jid=?
                    """, canonical);
            jdbc.update("""
                    update processed_message set shadow_payload=jsonb_set(shadow_payload,'{participantJid}',to_jsonb(canonical_participant_jid))
                    where shadow_payload is not null and canonical_participant_jid=?
                    """, canonical);
        }
        jdbc.update("""
                update simulation_batches b set status='COMPLETED',claimed_at=null
                 where b.status in ('OPEN','READY','CLAIMED')
                   and not exists(select 1 from simulation_batch_messages m where m.batch_id=b.batch_id)
                """);
    }


    public void mergeProcessedMessageIdentities(String canonical, Set<String> aliases) {
        String placeholders = String.join(",", java.util.Collections.nCopies(aliases.size(), "?"));
        List<MessageDedupRow> rows = jdbc.query("select id,canonical_chat_jid,upstream_message_key,canonical_participant_jid,processed_at "
                        + "from processed_message where canonical_chat_jid in (" + placeholders
                        + ") or canonical_participant_jid in (" + placeholders + ") order by id for update",
                (rs, row) -> new MessageDedupRow(rs.getLong("id"), rs.getString("canonical_chat_jid"),
                        rs.getString("upstream_message_key"), rs.getString("canonical_participant_jid"),
                        rs.getTimestamp("processed_at").toInstant()), concat(aliases, aliases).toArray());
        Map<MessageDedupKey, MessageDedupRow> latestByKey = new LinkedHashMap<>();
        List<Long> duplicates = new ArrayList<>();
        for (MessageDedupRow row : rows) {
            MessageDedupKey key = new MessageDedupKey(
                    aliases.contains(row.chatJid()) ? canonical : row.chatJid(), row.upstreamMessageKey(),
                    aliases.contains(row.participantJid()) ? canonical : row.participantJid());
            MessageDedupRow prior = latestByKey.get(key);
            if (prior == null) latestByKey.put(key, row);
            else if (row.processedAt().isAfter(prior.processedAt())) {
                duplicates.add(prior.id());
                latestByKey.put(key, row);
            } else duplicates.add(row.id());
        }
        if (!duplicates.isEmpty()) {
            String ids = String.join(",", java.util.Collections.nCopies(duplicates.size(), "?"));
            jdbc.update("delete from processed_message where id in (" + ids + ")", duplicates.toArray());
        }
        for (Map.Entry<MessageDedupKey, MessageDedupRow> entry : latestByKey.entrySet()) {
            MessageDedupKey key = entry.getKey();
            jdbc.update("update processed_message set canonical_chat_jid=?, canonical_participant_jid=? where id=?",
                    key.chatJid(), key.participantJid(), entry.getValue().id());
        }
    }


    private static List<Object> concat(Set<String> first, Set<String> second) {
        List<Object> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }



    public void observeAliasIfAbsent(String jid) {
        jdbc.update("""
                insert into identity_aliases(alias, canonical_identity) values (?, ?)
                on conflict (alias) do nothing
                """, jid, jid);
    }

    /** Only used inside the service's existing transaction. */
    public void lockAliasMerge() {
        jdbc.query("select pg_advisory_xact_lock(947221, 2)", rs -> { });
        jdbc.execute("lock table identity_aliases in share row exclusive mode");
    }

    public List<String> canonicalsForAliases(List<String> aliases) {
        if (aliases.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(aliases.size(), "?"));
        return jdbc.query("select canonical_identity from identity_aliases where alias in (" + placeholders + ") order by alias",
                (rs, row) -> rs.getString(1), aliases.toArray());
    }

    public List<String> aliasesForCanonicals(Set<String> canonicals) {
        if (canonicals.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(canonicals.size(), "?"));
        return jdbc.query("select alias from identity_aliases where canonical_identity in (" + placeholders + ") order by alias",
                (rs, row) -> rs.getString(1), canonicals.toArray());
    }

    public void rekeyCanonicals(String canonical, Set<String> oldCanonicals) {
        if (oldCanonicals.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(oldCanonicals.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(canonical);
        args.addAll(oldCanonicals);
        jdbc.update("update identity_aliases set canonical_identity=? where canonical_identity in (" + placeholders + ")",
                args.toArray());
    }

    public void upsertAlias(String alias, String canonical) {
        jdbc.update("""
                insert into identity_aliases(alias,canonical_identity) values (?,?)
                on conflict (alias) do update set canonical_identity=excluded.canonical_identity
                """, alias, canonical);
    }

    public List<AliasRow> aliasRows(List<String> aliases, boolean sharedLock) {
        if (aliases.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(aliases.size(), "?"));
        return jdbc.query("select alias,canonical_identity from identity_aliases where alias in (" + placeholders
                        + ") order by alias" + (sharedLock ? " for share" : ""),
                (rs, row) -> new AliasRow(rs.getString("alias"), rs.getString("canonical_identity")), aliases.toArray());
    }

    public void lockSnapshot(String jid) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, jid);
    }


    public List<ContactRow> contactsForCanonical(String canonical, boolean forUpdate) {
        return jdbc.query("""
                select canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at
                  from contact_identity where canonical_jid=?
                """ + (forUpdate ? " for update" : ""), IdentityMergeRepository::contact, canonical);
    }

    public List<ContactRow> contactsForCanonicals(List<String> canonicals) {
        if (canonicals.isEmpty()) return List.of();
        String params = String.join(",", java.util.Collections.nCopies(canonicals.size(), "?"));
        return jdbc.query("""
                select canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at
                  from contact_identity where canonical_jid in (""" + params + ") order by canonical_jid for update",
                IdentityMergeRepository::contact, canonicals.toArray());
    }

    public void upsertContactName(String target, String lid, String phone, String username,
                                  String selectedName, ContactNameSource selectedSource) {
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
                """, target, lid, phone, username, selectedName,
                selectedSource == null ? null : selectedSource.name(), Timestamp.from(Instant.now()));
    }

    public void updateSnapshotDisplayName(String canonical, String displayName) {
        jdbc.update("update chat_snapshot set display_name=? where chat_jid=?", displayName, canonical);
    }

    public void replaceContacts(List<String> candidates, String canonical, String lid, String phone,
                                String username, String displayName, ContactNameSource source) {
        if (candidates.isEmpty()) throw new IllegalArgumentException("Empty identity merge");
        String params = String.join(",", java.util.Collections.nCopies(candidates.size(), "?"));
        jdbc.update("delete from contact_identity where canonical_jid in (" + params + ")", candidates.toArray());
        jdbc.update("""
                insert into contact_identity(canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at)
                values (?,?,?,?,?,?,?)
                """, canonical, lid, phone, username, displayName, source == null ? null : source.name(),
                Timestamp.from(Instant.now()));
    }

    public List<ChatSnapshotRow> snapshotsForUpdate(List<String> jids) {
        if (jids.isEmpty()) return List.of();
        String params = String.join(",", java.util.Collections.nCopies(jids.size(), "?"));
        return jdbc.query("""
                select chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot,last_read_at
                  from chat_snapshot where chat_jid in (""" + params + ") order by chat_jid for update",
                (rs, row) -> new ChatSnapshotRow(rs.getString("chat_jid"), (Boolean) rs.getObject("archived"),
                        rs.getTimestamp("snapshot_at").toInstant(), rs.getString("display_name"),
                        rs.getBoolean("is_group"), rs.getBoolean("synchronized_snapshot"),
                        rs.getTimestamp("last_read_at") == null ? null : rs.getTimestamp("last_read_at").toInstant()),
                jids.toArray());
    }

    public List<PendingRow> pendingForUpdate(List<String> jids) {
        if (jids.isEmpty()) return List.of();
        String params = String.join(",", java.util.Collections.nCopies(jids.size(), "?"));
        return jdbc.query("""
                select chat_jid,payload::text as payload,due_at,updated_at,expires_at
                  from pending_notification where chat_jid in (""" + params + ") order by chat_jid for update",
                (rs, row) -> new PendingRow(rs.getString("chat_jid"), rs.getString("payload"),
                        rs.getTimestamp("due_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()), jids.toArray());
    }

    public void replaceSnapshots(List<String> jids, String canonical, Boolean archived, Instant snapshotAt,
                                 String displayName, boolean group, boolean synchronizedSnapshot, Instant lastReadAt) {
        if (jids.isEmpty()) throw new IllegalArgumentException("Empty identity merge");
        String params = String.join(",", java.util.Collections.nCopies(jids.size(), "?"));
        jdbc.update("delete from chat_snapshot where chat_jid in (" + params + ")", jids.toArray());
        jdbc.update("""
                insert into chat_snapshot(chat_jid,archived,snapshot_at,display_name,is_group,synchronized_snapshot,last_read_at)
                values (?,?,?,?,?,?,?)
                """, canonical, archived, Timestamp.from(snapshotAt), displayName, group,
                synchronizedSnapshot, lastReadAt == null ? null : Timestamp.from(lastReadAt));
    }

    public void deletePending(List<String> jids) {
        if (jids.isEmpty()) return;
        String params = String.join(",", java.util.Collections.nCopies(jids.size(), "?"));
        jdbc.update("delete from pending_notification where chat_jid in (" + params + ")", jids.toArray());
    }

    public void insertPending(String canonical, String json, Instant dueAt, Instant updatedAt, Instant expiresAt) {
        jdbc.update("""
                insert into pending_notification(chat_jid,payload,due_at,updated_at,expires_at)
                values (?,?::jsonb,?,?,?)
                """, canonical, json, Timestamp.from(dueAt), Timestamp.from(updatedAt), Timestamp.from(expiresAt));
    }

    public void quarantine(String kind, String externalId, String reason, Instant at) {
        QuarantineWriter.write(jdbc, kind, externalId, reason, at);
    }

    private static ContactRow contact(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ContactRow(rs.getString("canonical_jid"), rs.getString("lid_jid"),
                rs.getString("phone_jid"), rs.getString("username"), rs.getString("display_name"),
                source(rs.getString("display_name_source")), rs.getTimestamp("updated_at").toInstant());
    }

    private static ContactNameSource source(String value) {
        if (value == null) return null;
        try { return ContactNameSource.valueOf(value); }
        catch (IllegalArgumentException ex) { return ContactNameSource.LEGACY; }
    }

    public record ContactRow(String canonicalJid, String lidJid, String phoneJid, String username,
                             String displayName, ContactNameSource nameSource, Instant updatedAt) { }
    public record ChatSnapshotRow(String chatJid, Boolean archived, Instant snapshotAt, String displayName,
                                  boolean group, boolean synchronizedSnapshot, Instant lastReadAt) { }
    public record PendingRow(String chatJid, String payload, Instant dueAt, Instant updatedAt, Instant expiresAt) { }

    public record AliasRow(String alias, String canonicalIdentity) { }

    private record MessageDedupRow(long id, String chatJid, String upstreamMessageKey,
                                   String participantJid, Instant processedAt) { }
    private record MessageDedupKey(String chatJid, String upstreamMessageKey, String participantJid) { }
}
