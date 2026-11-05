package dev.hugo.watcher.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Persisted admin opt-ins; aliases are resolved in PostgreSQL, never guessed from contact names. */
@Repository
public class ContactNotificationRuleRepository {
    private final JdbcTemplate jdbc;

    public ContactNotificationRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Rule(String selectorType, String selector, String profile, String priority) { }

    public List<Rule> forChat(String canonicalJid) {
        return jdbc.query("""
                select selector_type,selector,profile_name,priority from contact_notification_rules
                where selector_type='MARKER'
                   or (selector_type='CONTACT' and (selector=?
                       or selector in (select alias from identity_aliases where canonical_identity=?)))
                order by selector_type,selector
                """, (rs, n) -> new Rule(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4)), canonicalJid, canonicalJid);
    }

    public List<Rule> all() {
        return jdbc.query("""
                select selector_type,selector,profile_name,priority from contact_notification_rules
                order by selector_type,selector
                """, (rs, n) -> new Rule(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4)));
    }

    /** Called inside the existing Telegram command transaction after explicit confirmation. */
    public boolean save(String type, String selector, String profile, String priority, long actor, Instant now) {
        require(type, selector, profile, priority, actor, now);
        List<Rule> previous = locked(type, selector);
        Rule old = previous.isEmpty() ? null : previous.getFirst();
        if (old != null && Objects.equals(old.profile(), profile) && old.priority().equals(priority)) return false;
        jdbc.update("""
                insert into contact_notification_rules
                    (selector_type,selector,profile_name,priority,updated_by,updated_at) values (?,?,?,?,?,?)
                on conflict(selector_type,selector) do update set
                    profile_name=excluded.profile_name,priority=excluded.priority,
                    updated_by=excluded.updated_by,updated_at=excluded.updated_at
                """, type, selector, profile, priority, actor, Timestamp.from(now));
        audit(type, selector, "UPSERT", old, new Rule(type, selector, profile, priority), actor, now);
        return true;
    }

    public boolean remove(String type, String selector, long actor, Instant now) {
        require(type, selector, null, "NORMAL", actor, now);
        List<Rule> previous = locked(type, selector);
        if (previous.isEmpty()) return false;
        Rule old = previous.getFirst();
        jdbc.update("delete from contact_notification_rules where selector_type=? and selector=?", type, selector);
        audit(type, selector, "REMOVE", old, null, actor, now);
        return true;
    }

    private List<Rule> locked(String type, String selector) {
        return jdbc.query("""
                select selector_type,selector,profile_name,priority from contact_notification_rules
                 where selector_type=? and selector=? for update
                """, (rs, n) -> new Rule(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4)), type, selector);
    }

    private void audit(String type, String selector, String action, Rule previous, Rule next,
                       long actor, Instant now) {
        jdbc.update("""
                insert into contact_notification_rule_audit
                    (selector_type,selector,action,previous_profile,previous_priority,
                     next_profile,next_priority,actor_user_id,changed_at)
                values (?,?,?,?,?,?,?,?,?)
                """, type, selector, action, previous == null ? null : previous.profile(),
                previous == null ? null : previous.priority(), next == null ? null : next.profile(),
                next == null ? null : next.priority(), actor, Timestamp.from(now));
    }

    private static void require(String type, String selector, String profile,
                                String priority, long actor, Instant now) {
        if (!List.of("CONTACT", "MARKER").contains(type)
                || selector == null || selector.length() > 255
                || (type.equals("MARKER") && !selector.matches("[A-Z0-9]{2,32}"))
                || (type.equals("CONTACT") && !selector.matches("[\\p{L}\\p{N}_.:@+-]{3,255}"))
                || (profile != null && !List.of("WORK_ONLY", "ANYTIME").contains(profile))
                || !List.of("NORMAL", "PRIORITY").contains(priority)
                || actor <= 0 || now == null) throw new IllegalArgumentException("invalid_notification_rule");
    }
}
