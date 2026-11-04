package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Native read projection for notification delivery and connection-alert generation.
 * Call within NotificationService's existing enqueue transaction.
 */
@Repository
public class NotificationQueueQueryRepository {
    private final JdbcTemplate jdbc;

    public NotificationQueueQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean currentOutageGeneration(long expected) {
        List<ConnectionState> current = jdbc.query("""
                select connection_state,outage_generation from watcher_connection_state where singleton_id=1
                """, (rs, row) -> new ConnectionState(rs.getString("connection_state"), rs.getLong("outage_generation")));
        return !current.isEmpty() && "DISCONNECTED".equals(current.getFirst().connectionState())
                && current.getFirst().generation() == expected;
    }

    /** The mapping remains owned by the notification presenter; repository owns the SQL. */
    public <T> List<T> duePending(Instant now, RowMapper<T> mapper) {
        return jdbc.query("""
                select p.chat_jid, p.generation::text as pending_generation, p.payload::text as payload, p.updated_at, p.expires_at,
                       s.archived, coalesce(s.synchronized_snapshot, false) as synchronized_snapshot,
                       s.display_name as chat_name, coalesce(s.is_group, p.chat_jid like '%@g.us') as is_group,
                       coalesce(alias.canonical_identity, p.chat_jid) as canonical_jid,
                       case when identity.display_name_source='CONTACT_NAME' then identity.display_name
                            else coalesce(identity.display_name,identity.manual_display_name) end as identity_name,
                       identity.phone_jid,
                       identity.lid_jid, identity.username,
                       coalesce(preview_sender.display_name,preview_sender.manual_display_name,preview_sender.username)
                           as preview_sender_name
                from pending_notification p
                left join chat_snapshot s on s.chat_jid=p.chat_jid
                left join identity_aliases alias on alias.alias=p.chat_jid
                left join contact_identity identity
                    on identity.canonical_jid=coalesce(alias.canonical_identity, p.chat_jid)
                left join identity_aliases preview_sender_alias on preview_sender_alias.alias=p.payload->>'previewSenderJid'
                left join contact_identity preview_sender
                    on preview_sender.canonical_jid=coalesce(preview_sender_alias.canonical_identity,p.payload->>'previewSenderJid')
                where p.due_at <= ? and p.expires_at > ?
                order by p.due_at,p.chat_jid
                """, mapper, Timestamp.from(now), Timestamp.from(now));
    }

    public void discardBlockedGroup(String chatJid) {
        jdbc.update("delete from pending_notification where chat_jid=?", chatJid);
    }

    /** Only an exact, unique retained private-message decision can suppress legacy delivery. */
    public boolean hasUniqueTriageIgnore(String chatJid, Instant messageAt, Instant now) {
        if (chatJid == null || messageAt == null || now == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select count(*)=1
                  from triage_legacy_filter_opt_ins opt
                  join simulation_batch_messages m on m.chat_jid=opt.chat_jid
                  join simulation_batches b on b.batch_id=m.batch_id and b.chat_jid=m.chat_jid
                  join simulation_decisions d on d.batch_id=b.batch_id
                 where opt.chat_jid=? and opt.enabled
                   and opt.chat_jid not like '%@g.us' and opt.chat_jid not like '%@broadcast'
                   and b.participant_jid='' and b.status='COMPLETED'
                   and m.received_at=? and d.decided_at>=m.received_at
                   and b.expires_at>? and d.metadata_expires_at>?
                   and not d.production_mode and d.classification_status='CLASSIFIED'
                   and d.triage_outcome='IGNORE' and d.decision_state='NO_ACTION' and d.blocked_reason is null
                   and not d.notification_recommended and not d.reply_recommended
                """, Boolean.class, chatJid, Timestamp.from(messageAt), Timestamp.from(now), Timestamp.from(now)));
    }

    /** Deletes only the pending generation that was evaluated; a concurrent message keeps its new row. */
    public int discardPendingIfCurrent(String chatJid, String generation, Instant updatedAt,
                                       Instant lastMessageAt, int messageCount) {
        if (chatJid == null || generation == null || updatedAt == null || lastMessageAt == null || messageCount != 1) return 0;
        return jdbc.update("""
                delete from pending_notification
                 where chat_jid=? and generation=?::uuid and updated_at=?
                   and payload->>'lastMessageAt'=? and coalesce((payload->>'messageCount')::integer,0)=?
                """, chatJid, generation, Timestamp.from(updatedAt), lastMessageAt.toString(), messageCount);
    }

    private record ConnectionState(String connectionState, long generation) { }
}
