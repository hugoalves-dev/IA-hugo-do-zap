package dev.hugo.watcher.notification;


/** Default transport never contacts Evolution; deployments must install an explicit transport. */
public class DisabledEvolutionActionTransport implements EvolutionActionTransport {
    @Override public EvolutionSendResult send(String chatJid, String text, String idempotencyKey) {
        return EvolutionSendResult.failed();
    }
}
