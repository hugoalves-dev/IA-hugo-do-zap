package dev.hugo.watcher.telegram;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class ContactPolicyOverviewRepository {
    private final JdbcTemplate jdbc;

    public ContactPolicyOverviewRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean known(String chat) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from chat_snapshot where chat_jid=?)", Boolean.class, chat));
    }

    public String displayName(String chat) {
        List<String> names = jdbc.query("select display_name from chat_snapshot where chat_jid=?", (rs, row) -> rs.getString(1), chat);
        return names.isEmpty() ? null : names.getFirst();
    }

    public String fallbackProfile(String chat, String allowedNameRegex) {
        String name = displayName(chat);
        return name != null && java.util.regex.Pattern.compile(allowedNameRegex, java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(name).find() ? "WORK_ONLY" : "ANYTIME";
    }

    public List<String> authorizations(String chat) {
        return jdbc.query("select distinct situation from chat_automation_authorizations where chat_jid=? order by situation",
                (rs, row) -> rs.getString(1), chat);
    }

    public List<String> consents(String chat, Instant now) {
        return jdbc.query("""
                select provider || ':' || situation from ai_data_consents
                 where chat_jid=? and privacy_approved=true and (expires_at is null or expires_at>?)
                 order by provider,situation
                """, (rs, row) -> rs.getString(1), chat, Timestamp.from(now));
    }
}
