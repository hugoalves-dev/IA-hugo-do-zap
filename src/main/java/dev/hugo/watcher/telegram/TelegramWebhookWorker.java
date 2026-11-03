package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.utility.BotUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;

/** All database effects and DONE state share one transaction; HTTP callbacks happen after commit. */
@Component
@ConditionalOnProperty(prefix = "watcher.telegram.admin", name = "webhook-enabled", havingValue = "true")
public class TelegramWebhookWorker {
    private static final System.Logger LOG = System.getLogger(TelegramWebhookWorker.class.getName());
    private final TelegramWebhookInboxRepository inbox;
    private final TransactionTemplate transactions;
    private final TelegramUpdateHandler handler;
    private final TelegramCallbackAcknowledger callbacks;

    public TelegramWebhookWorker(TelegramWebhookInboxRepository inbox, PlatformTransactionManager manager,
                                 TelegramUpdateHandler handler, TelegramCallbackAcknowledger callbacks) {
        this.inbox = inbox;
        this.transactions = new TransactionTemplate(manager);
        this.handler = handler;
        this.callbacks = callbacks;
    }

    @Scheduled(fixedDelay = 1000)
    public void drain() {
        for (int i = 0; i < 10; i++) {
            AtomicReference<Long> failedId = new AtomicReference<>();
            try {
                Processed result = transactions.execute(status -> processOne(failedId));
                if (result == null) return;
                if (result.callbackId() != null) callbacks.answer(result.callbackId(), result.feedback());
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "Telegram webhook worker failed: {0}", ex.getClass().getSimpleName());
                if (failedId.get() != null) {
                    try {
                        inbox.recordFailure(failedId.get());
                    } catch (RuntimeException ignored) {
                        LOG.log(System.Logger.Level.WARNING, "Telegram inbox backoff failed");
                    }
                }
                return;
            }
        }
    }

    private Processed processOne(AtomicReference<Long> processingId) {
        var rows = inbox.pendingForUpdate();
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        processingId.set(row.updateId());
        Update update = BotUtils.parseUpdate(row.payload());
        AdminResult result = handler.handle(update);
        inbox.markDone(row.updateId());
        var callback = update.callbackQuery();
        return new Processed(callback == null ? null : callback.id(),
                result.accepted() ? "✅ Comando recebido." : result.message());
    }

    /** Completed updates contain no message bodies. Prune metadata after 7 days. */
    @Scheduled(cron = "0 12 3 * * *", zone = "America/Sao_Paulo")
    public void purge() {
        inbox.purgeCompleted();
    }

    private record Processed(String callbackId, String feedback) { }
}
