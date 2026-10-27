package dev.hugo.watcher.notification;

/** Narrow Evolution REST boundary. Tests use a fake and no production request is made by this interface. */
@FunctionalInterface
public interface EvolutionActionTransport {
    EvolutionSendResult send(String chatJid, String text, String idempotencyKey);
}
