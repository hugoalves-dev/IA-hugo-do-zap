package dev.hugo.watcher.notification;


/** Default transport never contacts Evolution; authenticated delivery receipts remain a task 5 prerequisite. */
public class DisabledEvolutionActionTransport implements EvolutionActionTransport {
    @Override public EvolutionSendResult send(String chatJid, String text, String idempotencyKey) {
        return EvolutionSendResult.unknown();
    }
}
