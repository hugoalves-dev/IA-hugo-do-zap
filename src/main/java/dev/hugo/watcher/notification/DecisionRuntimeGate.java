package dev.hugo.watcher.notification;

import dev.hugo.watcher.WatcherProperties;
import dev.hugo.watcher.policy.*;
import dev.hugo.watcher.simulation.PersonalReferenceEvidence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

/** Current privacy, scope, source and availability gates shared by both delivery channels. */
@Component
public final class DecisionRuntimeGate {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final WatcherProperties watcher;
    private final String ownerJid;
    private dev.hugo.watcher.OperationalState operational;
    @org.springframework.beans.factory.annotation.Autowired public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }
    public DecisionRuntimeGate(JdbcTemplate jdbc, Clock clock, WatcherProperties watcher,
                               @Value("${watcher.whatsapp-actions.owner-jid:}") String ownerJid) {
        this.jdbc=jdbc; this.clock=clock; this.watcher=watcher; this.ownerJid=ownerJid;
    }
    public boolean allows(UUID id, boolean alert) {
        if (operational == null || !operational.classification() || (alert ? !operational.alerts() : !operational.replies())) return false;
        var now=clock.instant();
        var rows=jdbc.query("""
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
        if(rows.isEmpty()) return false;
        var c=rows.getFirst();
        try {
            var result=JSON.readTree(c.metadata());
            if(!result.path("available").asBoolean(false) || !result.path("requestDetected").asBoolean(false)
                    || !c.status().equals(result.path("status").asString(""))
                    || !c.urgency().equals(result.path("urgency").asString(""))
                    || c.confidence()!=result.path("confidence").asDouble(-1)) return false;
            String provider=result.path("provider").asString("");
            if(!provider.equals("gemini") && !provider.equals("openrouter")) return false;
            var purposes=result.path("purposes");
            if(!purposes.isArray() || purposes.isEmpty()) return false;
            boolean chatPurpose=false;
            boolean criticalPurpose=false;
            for(var purpose:purposes) {
                String situation=purpose.asString("");
                if(operational.requireChatConsent() && !Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from ai_data_consents where chat_jid=? and provider=?
                        and situation=? and privacy_approved and expires_at>?)
                        """,Boolean.class,c.chat(),provider,situation,Timestamp.from(now)))) return false;
                chatPurpose|=situation.equals(c.chat().endsWith("@g.us")?"GROUP_TRIGGER":"CONTACT_REQUEST");
                criticalPurpose|=situation.equals("CRITICAL_URGENCY");
            }
            boolean critical="CRITICAL".equals(c.urgency());
            if(!chatPurpose || critical && !criticalPurpose) return false;
            if(critical || c.chat().endsWith("@g.us") || alert) {
                String proof=PersonalReferenceEvidence.findHash(jdbc,id,ownerJid,now);
                if(proof==null || !proof.equals(c.reference())) return false;
            }
            String name=c.displayName()==null?"":c.displayName();
            String fallback=java.util.regex.Pattern.compile(watcher.getAllowedNameRegex(),java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(name).find()?"WORK_ONLY":"ANYTIME";
            var policy=new PersistedPolicyRepository(jdbc,new PolicyResolver()).forChat(c.chat(),fallback);
            var availability=new AvailabilityService(new HolidayService(jdbc)).at(now,ZoneId.of(watcher.getTimezone()),policy);
            return availability.source()!=AvailabilitySource.NO_POLICY
                    && (!alert || availability.state()==AvailabilityState.AVAILABLE || critical);
        } catch(RuntimeException invalidEvidence) { return false; }
    }
    private record Context(String chat,String metadata,String urgency,String status,double confidence,String reference,String displayName) { }
}
