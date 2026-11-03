package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;
import com.google.gson.Gson;
import dev.hugo.watcher.RabbitEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Session advisory lock enforces one active polling instance for a Telegram bot. */
@Component
@ConditionalOnProperty(prefix = "watcher.telegram.admin", name = "polling-enabled", havingValue = "true")
public class TelegramUpdatePollingService {
    private static final Gson JSON = new Gson();
    private static final System.Logger LOG = System.getLogger(TelegramUpdatePollingService.class.getName());
    private final TelegramAdminPollingRepository polling;
    private final TelegramUpdatesClient telegram;
    private final TelegramUpdateHandler handler;
    private final TelegramCallbackAcknowledger callbacks;
    private final TransactionTemplate transactions;
    private final RabbitEventPublisher publisher;

    @Autowired
    public TelegramUpdatePollingService(TelegramAdminPollingRepository polling, TelegramUpdatesClient telegram,
                                        TelegramUpdateHandler handler, PlatformTransactionManager manager,
                                        TelegramCallbackAcknowledger callbacks,
                                        ObjectProvider<RabbitEventPublisher> publisher) {
        this(polling, telegram, handler, manager, callbacks, publisher.getIfAvailable());
    }

    public TelegramUpdatePollingService(TelegramAdminPollingRepository polling, TelegramUpdatesClient telegram,
                                        TelegramUpdateHandler handler, PlatformTransactionManager manager,
                                        TelegramCallbackAcknowledger callbacks, RabbitEventPublisher publisher) {
        this.polling = polling;
        this.telegram = telegram;
        this.handler = handler;
        this.callbacks = callbacks;
        this.transactions = new TransactionTemplate(manager);
        this.publisher = publisher;
    }

    public TelegramUpdatePollingService(TelegramAdminPollingRepository polling, TelegramUpdatesClient telegram,
                                        TelegramUpdateHandler handler, PlatformTransactionManager manager,
                                        TelegramCallbackAcknowledger callbacks) {
        this(polling, telegram, handler, manager, callbacks, (RabbitEventPublisher) null);
    }

    @Scheduled(fixedDelayString = "${watcher.telegram.admin.poll-delay:PT1S}")
    public void poll() {
        try {
            polling.withExclusivePollingLock(this::pollLocked);
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Telegram admin polling failed: error={0}", ex.getClass().getSimpleName());
        }
    }

    private void pollLocked() {
        long offset = polling.pollingOffset();
        List<Update> updates = telegram.getUpdates(offset);
        for (Update update : updates) {
            if (publisher != null) {
                if (update == null || update.updateId() == null || update.updateId() < 0)
                    throw new IllegalStateException("Telegram returned an invalid update");
                String updateId = update.updateId().toString();
                publisher.publish("admin", updateId, JSON.toJson(update));
                transactions.executeWithoutResult(status -> polling.advanceOffset(update.updateId().longValue() + 1));
                continue;
            }
            AdminResult result = transactions.execute(status -> {
                AdminResult handled = handler.handle(update);
                if (update != null && update.updateId() != null) {
                    polling.advanceOffset(update.updateId().longValue() + 1);
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
