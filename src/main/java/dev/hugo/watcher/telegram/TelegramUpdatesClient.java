package dev.hugo.watcher.telegram;

import com.pengrad.telegrambot.model.Update;

import java.util.List;

public interface TelegramUpdatesClient {
    List<Update> getUpdates(long offset);
}
