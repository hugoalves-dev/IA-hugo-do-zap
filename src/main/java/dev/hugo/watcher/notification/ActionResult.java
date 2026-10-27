package dev.hugo.watcher.notification;

/** Fail-closed result for an action request that lacks authorized conversation context. */
import java.util.UUID;

public record ActionResult(boolean accepted, String reason, UUID actionId) {
    public static ActionResult rejected(String reason) { return new ActionResult(false, reason, null); }
    public static ActionResult accepted(UUID actionId) { return new ActionResult(true, "QUEUED", actionId); }
}
