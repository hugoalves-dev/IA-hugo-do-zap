package dev.hugo.watcher.telegram;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Session-scoped exclusive polling lock. The callback must run on the same JDBC
 * connection that owns pg_try_advisory_lock; do not switch to a transaction lock.
 */
@Repository
public class TelegramAdminPollingRepository {
    private static final int LOCK_CLASS = 73_491;
    private static final int LOCK_ID = 22;
    private final JdbcTemplate jdbc;

    public TelegramAdminPollingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void withExclusivePollingLock(Runnable work) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement lock = connection.prepareStatement("select pg_try_advisory_lock(?,?)")) {
                lock.setInt(1, LOCK_CLASS);
                lock.setInt(2, LOCK_ID);
                try (ResultSet result = lock.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) return null;
                }
            }
            try {
                work.run();
            } finally {
                try (PreparedStatement unlock = connection.prepareStatement("select pg_advisory_unlock(?,?)")) {
                    unlock.setInt(1, LOCK_CLASS);
                    unlock.setInt(2, LOCK_ID);
                    unlock.execute();
                }
            }
            return null;
        });
    }

    public long pollingOffset() {
        return jdbc.queryForObject("select polling_offset from telegram_admin_settings where singleton_id=1", Long.class);
    }

    /** Call inside the same transaction as handler mutation. */
    public void advanceOffset(long nextOffset) {
        jdbc.update("""
                update telegram_admin_settings
                   set polling_offset=greatest(polling_offset,?),updated_at=now()
                 where singleton_id=1
                """, nextOffset);
    }
}
