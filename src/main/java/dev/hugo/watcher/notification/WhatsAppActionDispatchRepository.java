package dev.hugo.watcher.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL-native WhatsApp delivery transition store.
 * All updates execute inside the dispatcher's existing transactions.
 * A DISPATCHED/UNKNOWN result must never be reclaimed as PENDING by the repository.
 */
@Repository
public class WhatsAppActionDispatchRepository {
    private final JdbcTemplate jdbc;

    public WhatsAppActionDispatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int claimPending(UUID id, Instant now) {
        return jdbc.update("""
                update whatsapp_action_outbox set status='DISPATCHED',updated_at=?
                 where action_id=? and status='PENDING' and expires_at>?
                """, Timestamp.from(now), id, Timestamp.from(now));
    }

    public int markDelivered(UUID id, Instant now) {
        return jdbc.update("""
                update whatsapp_action_outbox set status='DELIVERED',updated_at=?
                 where action_id=? and status='DISPATCHED'
                """, Timestamp.from(now), id);
    }

    public void advanceCooldown(UUID id, Instant now) {
        jdbc.update("""
                insert into decision_cooldowns(chat_jid,participant_jid,last_real_action_at,last_real_decision_id)
                select chat_jid,'',?,decision_id from whatsapp_action_outbox where action_id=?
                on conflict(chat_jid,participant_jid) do update
                set last_real_action_at=excluded.last_real_action_at,last_real_decision_id=excluded.last_real_decision_id
                where decision_cooldowns.last_real_action_at is null
                   or decision_cooldowns.last_real_action_at<=excluded.last_real_action_at
                """, Timestamp.from(now), id);
    }

    public List<UUID> nextPending(Instant now) {
        return jdbc.query("select action_id from whatsapp_action_outbox where status='PENDING' and expires_at>? order by created_at limit 1",
                (rs, row) -> rs.getObject(1, UUID.class), Timestamp.from(now));
    }

    /** Unknown/delivered/failed/suppressed rows expire without resetting decision tombstones. */
    public int purgeExpired(Instant now) {
        return jdbc.update("""
                delete from whatsapp_action_outbox where action_id in (
                    select action_id from whatsapp_action_outbox where expires_at<=?
                     order by expires_at limit 1000 for update skip locked
                )
                """, Timestamp.from(now));
    }

    public int updateStatus(UUID id, String state, String error, Instant now) {
        return jdbc.update("update whatsapp_action_outbox set status=?,last_error=?,updated_at=? where action_id=? and status='DISPATCHED'",
                state, error, Timestamp.from(now), id);
    }

    public UUID decisionId(UUID id) {
        return jdbc.queryForObject("select decision_id from whatsapp_action_outbox where action_id=?", UUID.class, id);
    }
}
