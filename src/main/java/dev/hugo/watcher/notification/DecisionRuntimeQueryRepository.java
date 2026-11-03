package dev.hugo.watcher.notification;

import dev.hugo.watcher.simulation.PersonalReferenceEvidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Database projections for the current authorization gate. Does not decide permissions. */
@Repository
public class DecisionRuntimeQueryRepository {
    private final JdbcTemplate jdbc;

    public DecisionRuntimeQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Context> contextForDecision(UUID id, Instant now) {
        return jdbc.query("""
                select b.chat_jid,e.result::text,d.classification_urgency,d.classification_status,
                       d.classification_confidence,d.intelligent_alert_reference_hash,c.display_name
                from simulation_decisions d join simulation_batches b on b.batch_id=d.batch_id
                join simulation_ai_evaluations e on e.batch_id=d.batch_id
                cross join automation_settings a cross join archive_monitor_settings s
                left join chat_snapshot c on c.chat_jid=b.chat_jid
                left join decision_cooldowns cooldown on cooldown.chat_jid=b.chat_jid and cooldown.participant_jid=''
                where d.batch_id=? and b.expires_at>? and d.metadata_expires_at>?
                  and b.status in ('CLAIMED','COMPLETED') and a.singleton_id=1 and s.singleton_id=1
                  and d.real_action_allowed
                  and d.production_mode and d.decision_state='WOULD_ACT' and d.classification_status='CLASSIFIED'
                  and d.classification_confidence>=0.65 and e.result is not null
                  and (cooldown.last_real_action_at is null or cooldown.last_real_decision_id=d.batch_id
                      or cooldown.last_real_action_at + a.real_cooldown_seconds * interval '1 second' <= ?)
                  and (s.mode='ALL' or (c.synchronized_snapshot and c.archived is not null and
                      ((s.mode='ARCHIVED_ONLY' and c.archived) or (s.mode='UNARCHIVED_ONLY' and not c.archived))))
                """,(rs,row)->new Context(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getDouble(5),rs.getString(6),rs.getString(7)),id,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now));
    }

    public boolean hasConsent(String chat, String provider, String situation, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from ai_data_consents where chat_jid=? and provider=?
                        and situation=? and privacy_approved and expires_at>?)
                        """,Boolean.class,chat,provider,situation,Timestamp.from(now)));
    }

    public String findPersonalReferenceHash(UUID id, String ownerJid, Instant now) {
        return PersonalReferenceEvidence.findHash(jdbc,id,ownerJid,now);
    }

    public record Context(String chat,String metadata,String urgency,String status,double confidence,
                          String reference,String displayName) { }
}
