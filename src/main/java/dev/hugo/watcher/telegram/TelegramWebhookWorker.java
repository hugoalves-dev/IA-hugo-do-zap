package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.utility.BotUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** All database effects and DONE state share one transaction; HTTP callbacks happen after commit. */
@Component
@ConditionalOnProperty(prefix = "watcher.telegram.admin", name = "webhook-enabled", havingValue = "true")
public class TelegramWebhookWorker {
    private static final System.Logger LOG = System.getLogger(TelegramWebhookWorker.class.getName());
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TelegramUpdateHandler handler;
    private final TelegramCallbackAcknowledger callbacks;

    public TelegramWebhookWorker(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                 TelegramUpdateHandler handler, TelegramCallbackAcknowledger callbacks) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.handler = handler;
        this.callbacks = callbacks;
    }

    @Scheduled(fixedDelay = 1000)
    public void drain() {
        for (int i = 0; i < 10; i++) {
            try {
                Processed result = transactions.execute(status -> processOne());
                if (result == null) return;
                if (result.callbackId() != null) callbacks.answer(result.callbackId(), result.feedback());
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "Telegram webhook worker failed: {0}", ex.getClass().getSimpleName());
                try {
                    jdbc.update("""
                        UPDATE telegram_webhook_inbox SET attempts=attempts+1,
                            available_at=now() + interval '5 seconds' * least(attempts+1,60),
                            status=CASE WHEN attempts>=11 THEN 'DEAD' ELSE 'PENDING' END,
                            last_error='HandlerFailure'
                        WHERE update_id=(
                            SELECT update_id FROM telegram_webhook_inbox
                            WHERE status='PENDING' AND available_at<=now()
                            ORDER BY available_at,update_id LIMIT 1 FOR UPDATE SKIP LOCKED
                        )
                        """);
                } catch (RuntimeException ignored) {
                    LOG.log(System.Logger.Level.WARNING, "Telegram inbox backoff failed");
                }
                return;
            }
        }
    }

    private Processed processOne() {
        List<Item> rows = jdbc.query("""
                SELECT update_id,payload::text FROM telegram_webhook_inbox
                WHERE status='PENDING' AND available_at<=now()
                ORDER BY available_at,update_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new Item(rs.getLong(1), rs.getString(2)));
        if (rows.isEmpty()) return null;
        Item row = rows.getFirst();
        Update update = BotUtils.parseUpdate(row.payload());
        AdminResult result = handler.handle(update);
        jdbc.update("""
                UPDATE telegram_webhook_inbox SET status='DONE',completed_at=now(),
                    payload='{}'::jsonb,last_error=NULL WHERE update_id=?
                """, row.updateId());
        var callback = update.callbackQuery();
        return new Processed(callback == null ? null : callback.id(),
                result.accepted() ? "✅ Comando recebido." : result.message());
    }

    private record Item(long updateId, String payload) {}
    private record Processed(String callbackId, String feedback) {}
}
