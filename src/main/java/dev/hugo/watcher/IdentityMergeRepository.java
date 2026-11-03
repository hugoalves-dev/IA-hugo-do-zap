package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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


    private record MessageDedupRow(long id, String chatJid, String upstreamMessageKey,
                                   String participantJid, Instant processedAt) { }
    private record MessageDedupKey(String chatJid, String upstreamMessageKey, String participantJid) { }
}
