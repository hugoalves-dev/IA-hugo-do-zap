package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.AnswerCallbackQuery;
import dev.hugo.watcher.WatcherProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "watcher.telegram.admin", name = "webhook-enabled", havingValue = "true")
public class TelegramCallbackAcknowledger {
    private static final System.Logger LOG = System.getLogger(TelegramCallbackAcknowledger.class.getName());
    private final TelegramBot bot;

    public TelegramCallbackAcknowledger(WatcherProperties watcher) {
        this.bot = new TelegramBot(watcher.getTelegramBotToken());
    }

    public void answer(String callbackId, String text) {
        if (callbackId == null || callbackId.isBlank()) return;
        try {
            var response = bot.execute(new AnswerCallbackQuery(callbackId).text(
                    text == null || text.isBlank() ? "Recebido." :
                            text.substring(0, Math.min(180, text.length()))));
            if (response == null || !response.isOk())
                LOG.log(System.Logger.Level.WARNING, "Telegram callback answer rejected");
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Telegram callback answer failed: {0}", ex.getClass().getSimpleName());
        }
    }
}
