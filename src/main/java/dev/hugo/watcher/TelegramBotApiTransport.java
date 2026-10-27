package dev.hugo.watcher;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.BiFunction;

/** Telegram Bot API adapter. The bot token is held only in this process. */
@Component
public class TelegramBotApiTransport implements TelegramTransport {
    private static final System.Logger LOG = System.getLogger(TelegramBotApiTransport.class.getName());

    private final BiFunction<Long, String, SendResponse> execute;
    private final long chatId;

    @Autowired
    public TelegramBotApiTransport(WatcherProperties properties) {
        this(properties.getTelegramChatId(), createExecutor(properties.getTelegramBotToken()));
    }

    TelegramBotApiTransport(long chatId, BiFunction<Long, String, SendResponse> execute) {
        this.chatId = chatId;
        this.execute = execute;
    }

    @Override
    public TelegramDeliveryResult send(String text) {
        try {
            SendResponse response = execute.apply(chatId, text);
            if (response != null && response.isOk()) return TelegramDeliveryResult.success();
            LOG.log(System.Logger.Level.WARNING, "Telegram delivery rejected: errorCode={0}",
                    response == null ? "no-response" : response.errorCode());
            boolean rateLimited = response != null && response.errorCode() == 429;
            Duration retryAfter = rateLimited
                    && response.parameters() != null && response.parameters().retryAfter() != null
                    && response.parameters().retryAfter() > 0
                    ? Duration.ofSeconds(response.parameters().retryAfter()) : null;
            return rateLimited ? TelegramDeliveryResult.rateLimited(retryAfter) : TelegramDeliveryResult.failure();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Telegram delivery failed: error={0}", ex.getClass().getSimpleName());
            return TelegramDeliveryResult.failure();
        }
    }

    private static BiFunction<Long, String, SendResponse> createExecutor(String token) {
        TelegramBot bot = new TelegramBot(token);
        return (targetChatId, text) -> bot.execute(new SendMessage(targetChatId, text));
    }
}
