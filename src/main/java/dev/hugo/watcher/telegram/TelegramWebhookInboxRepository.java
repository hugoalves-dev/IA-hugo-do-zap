package dev.hugo.watcher.telegram;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Native PostgreSQL inbox processing: claim and completion must share the caller's
 * transaction. SKIP LOCKED prevents multiple workers from processing one update.
 */
@Repository
public class TelegramWebhookInboxRepository {
    private final JdbcTemplate jdbc;

    public TelegramWebhookInboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Item> pendingForUpdate() {
        return jdbc.query("""
                SELECT update_id,payload::text FROM telegram_webhook_inbox
                WHERE status='PENDING' AND available_at<=now()
                ORDER BY available_at,update_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new Item(rs.getLong(1), rs.getString(2)));
    }

    public void markDone(long updateId) {
        jdbc.update("""
                UPDATE telegram_webhook_inbox SET status='DONE',completed_at=now(),
                    payload='{}'::jsonb,last_error=NULL WHERE update_id=?
                """, updateId);
    }

    public void recordFailure(long updateId) {
        jdbc.update("""
                UPDATE telegram_webhook_inbox SET attempts=attempts+1,
                    available_at=now() + interval '5 seconds' * least(attempts+1,60),
                    status=CASE WHEN attempts>=11 THEN 'DEAD' ELSE 'PENDING' END,
                    payload=CASE WHEN attempts>=11 THEN '{}'::jsonb ELSE payload END,
                    completed_at=CASE WHEN attempts>=11 THEN now() ELSE completed_at END,
                    last_error='HandlerFailure'
                WHERE update_id=? AND status='PENDING'
                """, updateId);
    }

    public void purgeCompleted() {
        jdbc.update("""
                DELETE FROM telegram_webhook_inbox
                WHERE status IN ('DONE','DEAD') AND completed_at < now() - interval '7 days'
                """);
    }

    public record Item(long updateId, String payload) { }
}
