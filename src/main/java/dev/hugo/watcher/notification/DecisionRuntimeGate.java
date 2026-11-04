package dev.hugo.watcher.notification;

import dev.hugo.watcher.WatcherProperties;
import dev.hugo.watcher.policy.*;
import dev.hugo.watcher.simulation.PersonalReferenceEvidence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import dev.hugo.watcher.notification.DecisionRuntimeQueryRepository.Context;

/** Current privacy, scope, source and availability gates shared by both delivery channels. */
@Component
public final class DecisionRuntimeGate {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DecisionRuntimeQueryRepository queries;
    private final PersistedPolicyRepository policies;
    private final HolidayService holidays;
    private final Clock clock;
    private final WatcherProperties watcher;
    private final String ownerJid;
    private dev.hugo.watcher.OperationalState operational;
    @org.springframework.beans.factory.annotation.Autowired public void configureOperationalState(dev.hugo.watcher.OperationalState value) { this.operational = value; }
    /** Production wiring uses managed repositories, not the legacy JDBC-only facade. */
    public DecisionRuntimeGate(DecisionRuntimeQueryRepository queries, Clock clock,
                               WatcherProperties watcher, String ownerJid,
                               PersistedPolicyRepository policies, HolidayService holidays) {
        this.queries = queries;
        this.clock = clock;
        this.watcher = watcher;
        this.ownerJid = ownerJid;
        this.policies = policies;
        this.holidays = holidays;
    }

    /** Compatibility constructor retained for existing standalone tests. */
    public DecisionRuntimeGate(JdbcTemplate jdbc, Clock clock, WatcherProperties watcher, String ownerJid) {
        this(new DecisionRuntimeQueryRepository(jdbc), clock, watcher, ownerJid,
                new PersistedPolicyRepository(jdbc, new PolicyResolver()), new HolidayService(jdbc));
    }
    public boolean allows(UUID id, boolean alert) {
        if (operational == null || !operational.classification() || (alert ? !operational.alerts() : !operational.replies())) return false;
        var now=clock.instant();
        var rows=queries.contextForDecision(id,now);
        if(rows.isEmpty()) return false;
        var c=rows.getFirst();
        try {
            var result=JSON.readTree(c.metadata());
            if(!result.path("available").asBoolean(false)
                    || !c.status().equals(result.path("status").asString(""))
                    || !c.urgency().equals(result.path("urgency").asString(""))
                    || c.confidence()!=result.path("confidence").asDouble(-1)) return false;
            if (alert ? !result.path("notificationRecommended").asBoolean(false)
                    : (!result.path("requestDetected").asBoolean(false)
                        || !result.path("replyRecommended").asBoolean(false))) return false;
            String provider=result.path("provider").asString("");
            if(!provider.equals("gemini") && !provider.equals("openrouter")) return false;
            var purposes=result.path("purposes");
            if(!purposes.isArray() || purposes.isEmpty()) return false;
            boolean chatPurpose=false;
            boolean criticalPurpose=false;
            for(var purpose:purposes) {
                String situation=purpose.asString("");
                if(operational.requireChatConsent() && !queries.hasConsent(c.chat(),provider,situation,now)) return false;
                chatPurpose|=situation.equals(c.chat().endsWith("@g.us")?"GROUP_TRIGGER":"CONTACT_REQUEST");
                criticalPurpose|=situation.equals("CRITICAL_URGENCY");
            }
            boolean critical="CRITICAL".equals(c.urgency());
            if(!chatPurpose || critical && !criticalPurpose) return false;
            if(critical || "HIGH".equals(c.urgency()) || c.chat().endsWith("@g.us")) {
                String proof=queries.findPersonalReferenceHash(id,ownerJid,now);
                if(proof==null || !proof.equals(c.reference())) return false;
            }
            String name=c.displayName()==null?"":c.displayName();
            String fallback=java.util.regex.Pattern.compile(watcher.getAllowedNameRegex(),java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(name).find()?"WORK_ONLY":"ANYTIME";
            var policy=policies.forChat(c.chat(),fallback);
            var availability=new AvailabilityService(holidays).at(now,ZoneId.of(watcher.getTimezone()),policy);
            return availability.source()!=AvailabilitySource.NO_POLICY
                    && (!alert || availability.state()==AvailabilityState.AVAILABLE || critical);
        } catch(RuntimeException invalidEvidence) { return false; }
    }
}
