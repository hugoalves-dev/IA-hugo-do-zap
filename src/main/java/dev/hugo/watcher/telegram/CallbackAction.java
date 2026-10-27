package dev.hugo.watcher.telegram;

import java.util.List;

public record CallbackAction(String command, List<String> arguments) {
    public CallbackAction {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command is required");
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}
