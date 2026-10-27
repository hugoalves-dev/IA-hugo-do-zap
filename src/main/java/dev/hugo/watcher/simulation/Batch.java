package dev.hugo.watcher.simulation;

import dev.hugo.watcher.IncomingMessage;
import java.util.List;
import java.util.UUID;

public record Batch(UUID id, String chatJid, String participantJid, List<IncomingMessage> messages) {
    public Batch { messages = List.copyOf(messages); }
}
