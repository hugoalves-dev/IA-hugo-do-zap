package dev.hugo.watcher.telegram;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Token hashes only. Atomic UPDATE ... RETURNING enforces exactly-once confirmation. */
@Repository
public class CallbackTokenLedgerRepository {
    private final JdbcTemplate jdbc;

    public CallbackTokenLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void purgeExpiredOrConsumed(Instant at) {
        jdbc.update("delete from telegram_admin_callback_tokens where expires_at<=? or consumed_at is not null",
                Timestamp.from(at));
    }

    public void issue(String hash, long userId, long chatId, String actionJson, Instant createdAt, Instant expiresAt) {
        jdbc.update("""
                insert into telegram_admin_callback_tokens(token_hash,user_id,chat_id,action,created_at,expires_at)
                values (?,?,?,?::jsonb,?,?)
                """, hash, userId, chatId, actionJson, Timestamp.from(createdAt), Timestamp.from(expiresAt));
    }

    public List<String> consume(String hash, long userId, long chatId, Instant at) {
        return jdbc.query("""
                update telegram_admin_callback_tokens
                   set consumed_at=?
                 where token_hash=? and user_id=? and chat_id=? and consumed_at is null and expires_at>?
                returning action::text
                """, (rs, row) -> rs.getString(1), Timestamp.from(at), hash, userId, chatId, Timestamp.from(at));
    }

    public boolean invalidate(String hash, long userId, long chatId, Instant at) {
        return jdbc.update("""
                update telegram_admin_callback_tokens set consumed_at=?
                where token_hash=? and user_id=? and chat_id=? and consumed_at is null
                """, Timestamp.from(at), hash, userId, chatId) == 1;
    }
}
