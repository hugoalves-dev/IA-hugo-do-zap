package dev.hugo.watcher;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.model.request.ParseMode;
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
    private final BiFunction<Long, String, SendResponse> executeHtml;
    private final long chatId;

    @Autowired
    public TelegramBotApiTransport(WatcherProperties properties) {
        this(properties.getTelegramChatId(), new TelegramBot(properties.getTelegramBotToken()));
    }

    TelegramBotApiTransport(long chatId, BiFunction<Long, String, SendResponse> execute) {
        this(chatId, execute, execute);
    }

    private TelegramBotApiTransport(long chatId, TelegramBot bot) {
        this(chatId, (id, text) -> bot.execute(message(id, text, false)),
                (id, text) -> bot.execute(message(id, text, true)));
    }

    private TelegramBotApiTransport(long chatId, BiFunction<Long, String, SendResponse> execute,
                                    BiFunction<Long, String, SendResponse> executeHtml) {
        this.chatId = chatId;
        this.execute = execute;
        this.executeHtml = executeHtml;
    }

    @Override
    public TelegramDeliveryResult send(String text) { return sendWith(text, execute); }

    @Override public TelegramDeliveryResult sendHtml(String html) { return sendWith(html, executeHtml); }

    private TelegramDeliveryResult sendWith(String text, BiFunction<Long, String, SendResponse> executor) {
        try {
            SendResponse response = executor.apply(chatId, text);
            if (response == null) return TelegramDeliveryResult.unknown();
            if (response.isOk()) return TelegramDeliveryResult.success();
            if (response.errorCode()<=0 || response.errorCode()>=500) return TelegramDeliveryResult.unknown();
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
            return TelegramDeliveryResult.unknown();
        }
    }

    static SendMessage message(long chatId, String text, boolean html) {
        SendMessage request = new SendMessage(chatId, text);
        return html ? request.parseMode(ParseMode.HTML) : request;
    }
}
