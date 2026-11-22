package dev.hugo.watcher.notification;

import dev.hugo.watcher.simulation.PersonalReferenceEvidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * PostgreSQL-native dispatch admission, within the coordinator's existing transaction.
 * The ordering of outbox -> chat claim -> attempt is security-sensitive.
 * Claims and cooldowns are per channel: WhatsApp uses the reply cooldown, Telegram its own alert interval.
 * Do not replace the locked claims with ordinary entity save().
 */
@Repository
public class DeliveryAdmissionRepository {
    private final JdbcTemplate jdbc;

    public DeliveryAdmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean begin(UUID decisionId, String channel, UUID token, Instant now) {
            // Same order as ACK: outbox row, then conversation claim, then attempt/cooldown.
            // Holding the row prevents token/status replacement while admitting dispatch.
            String lockedSql="TELEGRAM".equals(channel)
                    ? "select id::text from outbox_actions where action_key='intelligent_alert:' || ?::text and action_type='intelligent_alert' and status='PROCESSING' and lease_token=? for update"
                    : "select action_id::text from whatsapp_action_outbox where decision_id=? and status='DISPATCHED' and action_id=? for update";
            if(jdbc.query(lockedSql,(rs,row)->rs.getString(1),decisionId,token).isEmpty()) return false;
            if(!currentOwnership(decisionId,channel,token)) return false;
            var chats=jdbc.query("select chat_jid from simulation_batches where batch_id=?",(rs,row)->rs.getString(1),decisionId);
            if(chats.isEmpty()) return false;
            String chat=chats.getFirst();
            boolean telegram="TELEGRAM".equals(channel);
            // WhatsApp keeps the pre-#66 shared key; Telegram serializes on its own row so that one channel's
            // in-flight or ambiguous delivery never mutes the other channel of the conversation.
            String sharedKey=PersonalReferenceEvidence.hash(chat);
            String key=telegram?PersonalReferenceEvidence.hash("TELEGRAM:"+chat):sharedKey;
            jdbc.update("insert into decision_delivery_claims(chat_key,decision_id) values (?,?) on conflict do nothing",key,decisionId);
            jdbc.queryForObject("select decision_id from decision_delivery_claims where chat_key=? for update",UUID.class,key);
            // Rows claimed before #66 may still name a Telegram attempt under the shared key.
            Boolean unresolved=jdbc.queryForObject("""
                    select exists(select 1 from decision_delivery_claims c join decision_delivery_attempts a on a.decision_id=c.decision_id
                    where c.chat_key in (?,?) and c.decision_id<>? and a.channel=? and a.status in ('DISPATCHED','UNKNOWN'))
                    """,Boolean.class,key,sharedKey,decisionId,channel);
            if(Boolean.TRUE.equals(unresolved)) return false;
            Boolean throttled=jdbc.queryForObject(telegram ? """
                    select exists(select 1 from decision_cooldowns c cross join automation_settings s
                    where c.chat_jid=? and c.participant_jid='' and s.singleton_id=1
                    and c.last_alert_at + s.alert_min_interval_seconds * interval '1 second'>?
                    and c.last_alert_decision_id is distinct from ?)
                    """ : """
                    select exists(select 1 from decision_cooldowns c cross join automation_settings s
                    where c.chat_jid=? and c.participant_jid='' and s.singleton_id=1
                    and c.last_real_action_at + s.real_cooldown_seconds * interval '1 second'>?
                    and c.last_real_decision_id is distinct from ?)
                    """,Boolean.class,chat,Timestamp.from(now),decisionId);
            if(Boolean.TRUE.equals(throttled)) return false;
            // The claim lock may have waited. Recheck expiry against DB time after every lock.
            if(!currentOwnership(decisionId,channel,token)) return false;
            jdbc.update("update decision_delivery_claims set decision_id=? where chat_key=?",decisionId,key);
            return jdbc.update("""
                    insert into decision_delivery_attempts(decision_id,channel,attempt_token,status,updated_at)
                    values (?,?,?,'DISPATCHED',?) on conflict(decision_id,channel) do update
                    set attempt_token=excluded.attempt_token,status='DISPATCHED',updated_at=excluded.updated_at
                    where decision_delivery_attempts.status='RETRYABLE'
                    """,decisionId,channel,token,Timestamp.from(now))==1;
    }

    private boolean currentOwnership(UUID decisionId,String channel,UUID token) {
        String sql="TELEGRAM".equals(channel)
                ? "select exists(select 1 from outbox_actions where action_key='intelligent_alert:' || ?::text and action_type='intelligent_alert' and status='PROCESSING' and lease_token=? and lease_expires_at>clock_timestamp() and sensitive_expires_at>clock_timestamp())"
                : "select exists(select 1 from whatsapp_action_outbox where decision_id=? and status='DISPATCHED' and action_id=? and expires_at>clock_timestamp())";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,decisionId,token));
    }

    /** End of the conversation's Telegram alert interval started by another decision, or null when none is active. */
    public Instant alertIntervalEndsAt(UUID decisionId, Instant now) {
        var ends=jdbc.query("""
                select c.last_alert_at + s.alert_min_interval_seconds * interval '1 second'
                from simulation_batches b join decision_cooldowns c on c.chat_jid=b.chat_jid and c.participant_jid=''
                cross join automation_settings s
                where b.batch_id=? and s.singleton_id=1 and c.last_alert_decision_id is distinct from ?
                  and c.last_alert_at + s.alert_min_interval_seconds * interval '1 second'>?
                """,(rs,row)->rs.getTimestamp(1).toInstant(),decisionId,decisionId,Timestamp.from(now));
        return ends.isEmpty()?null:ends.getFirst();
    }

    public void finish(UUID decisionId, String channel, UUID token, String outcome, Instant now) {
            jdbc.query("select chat_key from decision_delivery_claims where decision_id=? order by chat_key for update",rs->{},decisionId);
            jdbc.update("update decision_delivery_attempts set status=?,updated_at=? where decision_id=? and channel=? and attempt_token=? and status='DISPATCHED'",
                    outcome,Timestamp.from(now),decisionId,channel,token);
    }
}
