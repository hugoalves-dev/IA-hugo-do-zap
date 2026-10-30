package dev.hugo.watcher;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.response.SendResponse;
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup;
import com.pengrad.telegrambot.model.WebAppInfo;
import com.pengrad.telegrambot.model.request.InlineKeyboardButton;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.response.BaseResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
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
    private final TelegramBot bot;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    public TelegramBotApiTransport(WatcherProperties properties) {
        this(properties.getTelegramChatId(), new TelegramBot(properties.getTelegramBotToken()));
    }

    TelegramBotApiTransport(long chatId, BiFunction<Long, String, SendResponse> execute) {
        this(chatId, execute, execute);
    }

    private TelegramBotApiTransport(long chatId, TelegramBot bot) {
        this(chatId, (id, text) -> bot.execute(message(id, text, false)),
                (id, text) -> bot.execute(message(id, text, true)), bot);
    }

    private TelegramBotApiTransport(long chatId, BiFunction<Long, String, SendResponse> execute,
                                    BiFunction<Long, String, SendResponse> executeHtml) {
        this(chatId, execute, executeHtml, null);
    }

    private TelegramBotApiTransport(long chatId, BiFunction<Long, String, SendResponse> execute,
                                    BiFunction<Long, String, SendResponse> executeHtml, TelegramBot bot) {
        this.chatId = chatId;
        this.execute = execute;
        this.executeHtml = executeHtml;
        this.bot = bot;
    }

    @Override
    public TelegramDeliveryResult send(String text) { return sendWith(text, execute); }

    @Override public TelegramDeliveryResult sendHtml(String html) { return sendWith(html, executeHtml); }

    @Override
    public TelegramDeliveryResult sendAdmin(String text, String payload) {
        if (bot == null) return send(text);
        try {
            InlineKeyboardMarkup markup = markup(payload);
            if (markup == null) return send(text);
            return sendWith(text, (id, body) -> bot.execute(message(id, body, false).replyMarkup(markup)));
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Telegram admin markup failed: {0}", ex.getClass().getSimpleName());
            return TelegramDeliveryResult.failure();
        }
    }

    @Override
    public TelegramDeliveryResult editAdmin(String text, String payload) {
        if (bot == null) return sendAdmin(text, payload);
        try {
            JsonNode node = JSON.readTree(payload);
            int messageId = node.path("messageId").asInt(0);
            if (messageId <= 0) return sendAdmin(text, payload);
            var request = new EditMessageText(chatId, messageId, text);
            InlineKeyboardMarkup markup = markup(payload);
            if (markup != null) request.replyMarkup(markup);
            BaseResponse response = bot.execute(request);
            if (response == null || response.errorCode() >= 500) return TelegramDeliveryResult.unknown();
            return response.isOk() ? TelegramDeliveryResult.success() : TelegramDeliveryResult.failure();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Telegram admin message edit failed: {0}", ex.getClass().getSimpleName());
            return TelegramDeliveryResult.unknown();
        }
    }

    static InlineKeyboardMarkup markup(String payload) {
        JsonNode keyboard = JSON.readTree(payload).path("keyboard");
        if (!keyboard.isArray() || keyboard.isEmpty()) return null;
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        for (JsonNode row : keyboard) {
            if (!row.isArray()) continue;
            List<InlineKeyboardButton> buttons = new ArrayList<>();
            for (JsonNode item : row) {
                String text = item.path("text").asString("");
                String data = item.path("data").asString("");
                String url = item.path("url").asString("");
                String webAppUrl = item.path("webAppUrl").asString("");
                if (text.isBlank()) continue;
                InlineKeyboardButton button = new InlineKeyboardButton(text);
                if (!data.isBlank() && data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64)
                    button.callbackData(data);
                else if (webAppUrl.startsWith("https://")) button.webApp(new WebAppInfo(webAppUrl));
                else if (url.startsWith("https://")) button.url(url);
                else continue;
                buttons.add(button);
            }
            if (!buttons.isEmpty()) markup.addRow(buttons.toArray(new InlineKeyboardButton[0]));
        }
        return markup.inlineKeyboard().length == 0 ? null : markup;
    }

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
