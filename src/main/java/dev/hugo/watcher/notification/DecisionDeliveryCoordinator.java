package dev.hugo.watcher.notification;

import dev.hugo.watcher.simulation.PersonalReferenceEvidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Durable dispatch admission. Transactions end before HTTP; ambiguity never grants another send. */
public final class DecisionDeliveryCoordinator {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public DecisionDeliveryCoordinator(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.transactions=new TransactionTemplate(manager);
    }
    public boolean begin(UUID decisionId,String channel,UUID token,Instant now) {
        if(token==null || !("TELEGRAM".equals(channel)||"WHATSAPP".equals(channel))) return false;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            // Same order as ACK: outbox row, then conversation claim, then attempt/cooldown.
            // Holding the row prevents token/status replacement while admitting dispatch.
            String lockedSql="TELEGRAM".equals(channel)
                    ? "select id::text from outbox_actions where action_key='intelligent_alert:' || ?::text and action_type='intelligent_alert' and status='PROCESSING' and lease_token=? for update"
                    : "select action_id::text from whatsapp_action_outbox where decision_id=? and status='DISPATCHED' and action_id=? for update";
            if(jdbc.query(lockedSql,(rs,row)->rs.getString(1),decisionId,token).isEmpty()) return false;
            if(!currentOwnership(decisionId,channel,token)) return false;
            var chats=jdbc.query("select chat_jid from simulation_batches where batch_id=?",(rs,row)->rs.getString(1),decisionId);
            if(chats.isEmpty()) return false;
            String chat=chats.getFirst(); String key=PersonalReferenceEvidence.hash(chat);
            jdbc.update("insert into decision_delivery_claims(chat_key,decision_id) values (?,?) on conflict do nothing",key,decisionId);
            UUID current=jdbc.queryForObject("select decision_id from decision_delivery_claims where chat_key=? for update",UUID.class,key);
            if(!decisionId.equals(current)) {
                Boolean unresolved=jdbc.queryForObject("select exists(select 1 from decision_delivery_attempts where decision_id=? and status in ('DISPATCHED','UNKNOWN'))",Boolean.class,current);
                if(Boolean.TRUE.equals(unresolved)) return false;
            }
            Boolean cooldown=jdbc.queryForObject("""
                    select exists(select 1 from decision_cooldowns c cross join automation_settings s
                    where c.chat_jid=? and c.participant_jid='' and s.singleton_id=1
                    and c.last_real_action_at + s.real_cooldown_seconds * interval '1 second'>?
                    and c.last_real_decision_id is distinct from ?)
                    """,Boolean.class,chat,Timestamp.from(now),decisionId);
            if(Boolean.TRUE.equals(cooldown)) return false;
            // The claim lock may have waited. Recheck expiry against DB time after every lock.
            if(!currentOwnership(decisionId,channel,token)) return false;
            jdbc.update("update decision_delivery_claims set decision_id=? where chat_key=?",decisionId,key);
            return jdbc.update("""
                    insert into decision_delivery_attempts(decision_id,channel,attempt_token,status,updated_at)
                    values (?,?,?,'DISPATCHED',?) on conflict(decision_id,channel) do update
                    set attempt_token=excluded.attempt_token,status='DISPATCHED',updated_at=excluded.updated_at
                    where decision_delivery_attempts.status='RETRYABLE'
                    """,decisionId,channel,token,Timestamp.from(now))==1;
        }));
    }
    private boolean currentOwnership(UUID decisionId,String channel,UUID token) {
        String sql="TELEGRAM".equals(channel)
                ? "select exists(select 1 from outbox_actions where action_key='intelligent_alert:' || ?::text and action_type='intelligent_alert' and status='PROCESSING' and lease_token=? and lease_expires_at>clock_timestamp() and sensitive_expires_at>clock_timestamp())"
                : "select exists(select 1 from whatsapp_action_outbox where decision_id=? and status='DISPATCHED' and action_id=? and expires_at>clock_timestamp())";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,decisionId,token));
    }
    /** Joins an existing ACK transaction. Only its matching dispatched token can settle once. */
    public void finish(UUID decisionId,String channel,UUID token,String outcome,Instant now) {
        transactions.executeWithoutResult(status -> {
            jdbc.query("select chat_key from decision_delivery_claims where decision_id=? for update",rs->{},decisionId);
            jdbc.update("update decision_delivery_attempts set status=?,updated_at=? where decision_id=? and channel=? and attempt_token=? and status='DISPATCHED'",
                    outcome,Timestamp.from(now),decisionId,channel,token);
        });
    }
}
