package dev.hugo.watcher.simulation;

public record Decision(boolean wouldAlert, boolean wouldReply, String templateReference,
                       String reason, String blockedReason, boolean realActionAllowed,
                       String realBlockedReason, DecisionStatus status) {
    public Decision(boolean wouldAlert, boolean wouldReply, String templateReference,
                    String reason, String blockedReason, boolean realActionAllowed, String realBlockedReason) {
        this(wouldAlert, wouldReply, templateReference, reason, blockedReason, realActionAllowed,
                realBlockedReason, wouldAlert || wouldReply ? DecisionStatus.WOULD_ACT
                        : blockedReason != null ? DecisionStatus.BLOCKED : DecisionStatus.NO_ACTION);
    }
}
