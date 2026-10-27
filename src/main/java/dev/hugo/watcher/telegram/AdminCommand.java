package dev.hugo.watcher.telegram;

import java.util.List;

public record AdminCommand(String name, List<String> arguments, long userId, long chatId, boolean confirmed) {
    public AdminCommand {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("command name is required");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}
