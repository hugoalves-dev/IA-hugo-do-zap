package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.response.GetUpdatesResponse;
import dev.hugo.watcher.WatcherProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "watcher.telegram.admin", name = "polling-enabled", havingValue = "true")
public class PengradTelegramUpdatesClient implements TelegramUpdatesClient {
    private final TelegramBot bot;

    public PengradTelegramUpdatesClient(WatcherProperties watcher) {
        this.bot = new TelegramBot(watcher.getTelegramBotToken());
    }

    @Override
    public List<Update> getUpdates(long offset) {
        GetUpdatesResponse response = bot.execute(new GetUpdates().offset(Math.toIntExact(offset)).limit(100).timeout(20));
        if (response == null || !response.isOk() || response.updates() == null)
            throw new IllegalStateException("Telegram polling request failed");
        return response.updates();
    }
}
