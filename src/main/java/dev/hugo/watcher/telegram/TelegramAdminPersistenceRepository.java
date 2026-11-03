package dev.hugo.watcher.telegram;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * PostgreSQL persistence used by Telegram admin commands.
 * The command service retains confirmation, authorization and transaction boundaries.
 */
@Repository
public class TelegramAdminPersistenceRepository {
    private final JdbcTemplate jdbc;

    public TelegramAdminPersistenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Status> availability() {
        return jdbc.query("select state,expires_at from availability_overrides where singleton_id=1",
                (rs, row) -> new Status(rs.getString(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()));
    }

    public void setAvailability(String state, Instant now, Instant expiry) {
        jdbc.update("""
                insert into availability_overrides(singleton_id,state,starts_at,expires_at,updated_at)
                values (1,?,?,?,?) on conflict(singleton_id) do update
                    set state=excluded.state,starts_at=excluded.starts_at,expires_at=excluded.expires_at,updated_at=excluded.updated_at
                """, state, Timestamp.from(now), Timestamp.from(expiry), Timestamp.from(now));
    }

    public void setHolidayOverride(Instant starts, Instant expires, Instant now) {
        jdbc.update("""
                insert into availability_overrides(singleton_id,state,starts_at,expires_at,updated_at)
                values (1,'UNAVAILABLE',?,?,?) on conflict(singleton_id) do update
                    set state='UNAVAILABLE',starts_at=excluded.starts_at,expires_at=excluded.expires_at,updated_at=excluded.updated_at
                """, Timestamp.from(starts), Timestamp.from(expires), Timestamp.from(now));
    }

    public void setSimulationPaused(boolean paused, Instant now) {
        jdbc.update("update operational_restrictions set simulation_paused=?,updated_at=? where singleton_id=1",
                paused, Timestamp.from(now));
    }

    public Integer decisionsSince(Instant since) {
        return jdbc.queryForObject("select count(*) from simulation_decisions where decided_at >= ?",
                Integer.class, Timestamp.from(since));
    }

    public Integer alertsSince(Instant since) {
        return jdbc.queryForObject("select count(*) from simulation_decisions where decided_at >= ? and would_alert=true",
                Integer.class, Timestamp.from(since));
    }

    public Long reservedSince(Instant since) {
        return jdbc.queryForObject("select coalesce(sum(reserved_micros),0) from ai_budget_reservations where created_at >= ? and status <> 'RELEASED'",
                Long.class, Timestamp.from(since));
    }

    public void setEmergency(boolean latched, Instant now) {
        jdbc.update("update operational_restrictions set emergency_latched=?,updated_at=? where singleton_id=1",
                latched, Timestamp.from(now));
    }

    public List<String> profileNames() {
        return jdbc.query("select profile_name from policy_profiles order by profile_name", (rs, row) -> rs.getString(1));
    }

    public Integer knownChat(String chatJid) {
        return jdbc.queryForObject("select count(*) from chat_snapshot where chat_jid=?", Integer.class, chatJid);
    }

    public Integer knownProfile(String name) {
        return jdbc.queryForObject("select count(*) from policy_profiles where profile_name=?", Integer.class, name);
    }

    public void setChatProfile(String chat, String profile, Instant now) {
        jdbc.update("""
                insert into chat_policy_overrides(chat_jid,profile_name,fields,updated_at) values (?,?,'{}'::jsonb,?)
                on conflict(chat_jid) do update set profile_name=excluded.profile_name,updated_at=excluded.updated_at
                """, chat, profile, Timestamp.from(now));
    }

    public List<String> templateNames() {
        return jdbc.query("select template_id || ' v' || version || case when enabled then ' (ativo)' else ' (inativo)' end from response_templates order by template_id,version",
                (rs, row) -> rs.getString(1));
    }

    public int setTemplateEnabled(boolean enabled, String id) {
        return jdbc.update("update response_templates set enabled=? where template_id=? and version=(select max(version) from response_templates where template_id=?)",
                enabled, id, id);
    }

    public List<String> holidaysBetween(LocalDate first, LocalDate last) {
        return jdbc.query("select local_date || ' ' || jurisdiction || ' ' || name || ' [' || validation_status || ']' from holiday_calendar where local_date between ? and ? order by local_date,jurisdiction limit 20",
                (rs, row) -> rs.getString(1), first, last);
    }

    public void upsertHoliday(LocalDate date, String jurisdiction, String name, String source, Instant now) {
        jdbc.update("""
                insert into holiday_calendar(local_date,jurisdiction,name,source,calendar_year,validation_status,optional,updated_at,
                    validated_by,validated_at)
                values (?,?,?,?,?,'VALIDATED',false,?,'telegram-admin',?)
                on conflict(local_date,jurisdiction,name) do update set source=excluded.source,
                    calendar_year=excluded.calendar_year,validation_status='VALIDATED',optional=false,
                    updated_at=excluded.updated_at,validated_by=excluded.validated_by,validated_at=excluded.validated_at
                """, date, jurisdiction, name, source, date.getYear(), Timestamp.from(now), Timestamp.from(now));
    }

    public List<Budget> budgetLimits() {
        return jdbc.query("select daily_limit_micros,monthly_limit_micros from ai_budget_limits where singleton_id=1",
                (rs, row) -> new Budget((Long) rs.getObject(1), (Long) rs.getObject(2)));
    }

    public void upsertBudget(long daily, long monthly, Instant now) {
        jdbc.update("""
                insert into ai_budget_limits(singleton_id,daily_limit_micros,monthly_limit_micros,updated_at)
                values (1,?,?,?) on conflict(singleton_id) do update set daily_limit_micros=excluded.daily_limit_micros,
                    monthly_limit_micros=excluded.monthly_limit_micros,updated_at=excluded.updated_at
                """, daily, monthly, Timestamp.from(now));
    }

    public void audit(String action, String outcome, long user, long chat, Instant now) {
        jdbc.update("insert into telegram_admin_audit(action,outcome,actor_user_id,actor_chat_id,created_at) values (?,?,?,?,?)",
                action, outcome, user, chat, Timestamp.from(now));
    }

    public record Status(String overrideState, Instant overrideExpiresAt) { }
    public record Budget(Long daily, Long monthly) { }
}
