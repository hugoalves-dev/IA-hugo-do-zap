package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/** One database advisory lock prevents another application instance polling this bot concurrently. */
@Component
@ConditionalOnProperty(prefix = "watcher.telegram.admin", name = "polling-enabled", havingValue = "true")
public class TelegramUpdatePollingService {
    private static final System.Logger LOG = System.getLogger(TelegramUpdatePollingService.class.getName());
    private static final int LOCK_CLASS = 73_491;
    private static final int LOCK_ID = 22;
    private final JdbcTemplate jdbc;
    private final TelegramUpdatesClient telegram;
    private final TelegramUpdateHandler handler;
    private final TelegramCallbackAcknowledger callbacks;
    private final TransactionTemplate transactions;

    @Autowired
    public TelegramUpdatePollingService(JdbcTemplate jdbc, TelegramUpdatesClient telegram,
                                        TelegramUpdateHandler handler, PlatformTransactionManager manager,
                                        TelegramCallbackAcknowledger callbacks) {
        this.jdbc = jdbc;
        this.telegram = telegram;
        this.handler = handler;
        this.callbacks = callbacks;
        this.transactions = new TransactionTemplate(manager);
    }

    public TelegramUpdatePollingService(JdbcTemplate jdbc, TelegramUpdatesClient telegram,
                                        TelegramUpdateHandler handler, PlatformTransactionManager manager) {
        this(jdbc, telegram, handler, manager, null);
    }

    public TelegramUpdatePollingService(JdbcTemplate jdbc, TelegramUpdatesClient telegram, TelegramUpdateHandler handler) {
        this(jdbc, telegram, handler, new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Scheduled(fixedDelayString = "${watcher.telegram.admin.poll-delay:PT1S}")
    public void poll() {
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                try (PreparedStatement lock = connection.prepareStatement("select pg_try_advisory_lock(?,?)")) {
                    lock.setInt(1, LOCK_CLASS);
                    lock.setInt(2, LOCK_ID);
                    try (ResultSet result = lock.executeQuery()) {
                        if (!result.next() || !result.getBoolean(1)) return null;
                    }
                }
                try {
                    pollLocked();
                } finally {
                    try (PreparedStatement unlock = connection.prepareStatement("select pg_advisory_unlock(?,?)")) {
                        unlock.setInt(1, LOCK_CLASS);
                        unlock.setInt(2, LOCK_ID);
                        unlock.execute();
                    }
                }
                return null;
            });
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Telegram admin polling failed: error={0}", ex.getClass().getSimpleName());
        }
    }

    private void pollLocked() {
        long offset = jdbc.queryForObject("select polling_offset from telegram_admin_settings where singleton_id=1", Long.class);
        List<Update> updates = telegram.getUpdates(offset);
        for (Update update : updates) {
            AdminResult result = transactions.execute(status -> {
                AdminResult handled = handler.handle(update);
                if (update != null && update.updateId() != null) {
                    long nextOffset = update.updateId().longValue() + 1;
                    jdbc.update("update telegram_admin_settings set polling_offset=greatest(polling_offset,?),updated_at=now() where singleton_id=1", nextOffset);
                }
                return handled;
            });
            // Answer only after the update and its durable offset are committed.
            if (callbacks != null && update != null && update.callbackQuery() != null) {
                callbacks.answer(update.callbackQuery().id(),
                        result != null && result.accepted() ? "✅ Comando recebido."
                                : result == null ? "Comando não processado." : result.message());
            }
        }
    }
}
