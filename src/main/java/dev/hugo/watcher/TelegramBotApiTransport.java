package dev.hugo.watcher;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.springframework.stereotype.Component;

/** Telegram Bot API adapter. The bot token is held only in this process. */
@Component
public class TelegramBotApiTransport implements TelegramTransport {
    private static final System.Logger LOG = System.getLogger(TelegramBotApiTransport.class.getName());

    private final TelegramBot bot;
    private final long chatId;

    public TelegramBotApiTransport(WatcherProperties properties) {
        this.bot = new TelegramBot(properties.getTelegramBotToken());
        this.chatId = properties.getTelegramChatId();
    }

    @Override
    public boolean send(String text) {
        try {
            SendResponse response = bot.execute(new SendMessage(chatId, text));
            if (response != null && response.isOk()) return true;
            LOG.log(System.Logger.Level.WARNING, "Telegram delivery rejected: errorCode={0}",
                    response == null ? "no-response" : response.errorCode());
            return false;
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Telegram delivery failed: error={0}", ex.getClass().getSimpleName());
            return false;
        }
    }
}
