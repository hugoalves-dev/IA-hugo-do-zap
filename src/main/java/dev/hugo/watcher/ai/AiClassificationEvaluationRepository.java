package dev.hugo.watcher.ai;

import dev.hugo.watcher.policy.Situation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Evaluation claims and current-consent projections; deny when evidence is missing. */
@Repository
public class AiClassificationEvaluationRepository {
    private final JdbcTemplate jdbc;

    public AiClassificationEvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int claim(UUID batchId, UUID token, Instant expiresAt) {
        return jdbc.update("""
                insert into simulation_ai_evaluations(batch_id,lease_token,expires_at) values (?,?,?)
                on conflict(batch_id) do nothing
                """, batchId, token, Timestamp.from(expiresAt));
    }

    public List<Prior> prior(UUID batchId) {
        return jdbc.query("select result::text,expires_at from simulation_ai_evaluations where batch_id=?",
                (rs, row) -> new Prior(rs.getString(1), rs.getTimestamp(2).toInstant()), batchId);
    }

    public int saveIfCurrent(UUID batchId, UUID token, String result, Instant now) {
        return jdbc.update("update simulation_ai_evaluations set result=?::jsonb where batch_id=? and lease_token=? and expires_at>?",
                result, batchId, token, Timestamp.from(now));
    }

    public List<String> storedResults(UUID batchId) {
        return jdbc.query("select result::text from simulation_ai_evaluations where batch_id=? and result is not null",
                (rs, row) -> rs.getString(1), batchId);
    }

    public List<Situation> consentedSituations(String chat, String provider, Instant now) {
        return jdbc.query("""
                select situation from ai_data_consents
                 where chat_jid=? and provider=? and privacy_approved=true and expires_at>?
                """, (rs, row) -> Situation.valueOf(rs.getString(1)), chat, provider, Timestamp.from(now));
    }

    public List<BatchScope> scopes(UUID batchId) {
        return jdbc.query("""
                select b.chat_jid,b.status,b.expires_at,s.mode,c.archived,
                       coalesce(c.synchronized_snapshot,false) synchronized_snapshot
                  from simulation_batches b
                  left join archive_monitor_settings s on s.singleton_id=1
                  left join chat_snapshot c on c.chat_jid=b.chat_jid
                 where b.batch_id=?
                """, (rs, row) -> new BatchScope(rs.getString("chat_jid"), rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("mode"),
                (Boolean) rs.getObject("archived"), rs.getBoolean("synchronized_snapshot")), batchId);
    }

    public record Prior(String result, Instant expires) { }
    public record BatchScope(String chatJid, String status, Instant expiresAt, String mode,
                             Boolean archived, boolean synchronizedSnapshot) { }
}
