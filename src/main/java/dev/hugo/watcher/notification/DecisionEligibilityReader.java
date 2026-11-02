package dev.hugo.watcher.notification;

import java.util.UUID;

/** Read-only production-action gate. False denies dispatch. */
public interface DecisionEligibilityReader {
    boolean isEligible(UUID decisionId);
}
