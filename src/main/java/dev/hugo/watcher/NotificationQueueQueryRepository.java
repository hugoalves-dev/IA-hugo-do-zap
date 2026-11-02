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
                       identity.display_name as identity_name, identity.phone_jid,
                       identity.lid_jid, identity.username
                from pending_notification p
                left join chat_snapshot s on s.chat_jid=p.chat_jid
                left join identity_aliases alias on alias.alias=p.chat_jid
                left join contact_identity identity
                    on identity.canonical_jid=coalesce(alias.canonical_identity, p.chat_jid)
                where p.due_at <= ? and p.expires_at > ?
                order by p.due_at,p.chat_jid
                """, mapper, Timestamp.from(now), Timestamp.from(now));
    }

    public void discardBlockedGroup(String chatJid) {
        jdbc.update("delete from pending_notification where chat_jid=?", chatJid);
    }

    private record ConnectionState(String connectionState, long generation) { }
}
