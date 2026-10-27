package dev.hugo.watcher.simulation;

public record SimulationDecision(boolean wouldAlert, boolean wouldReply, String templateId,
                                 String decisionReason, String blockedReason,
                                 boolean realActionAllowed, String realBlockedReason, DecisionStatus status) {
    public SimulationDecision(boolean wouldAlert, boolean wouldReply, String templateId,
                              String decisionReason, String blockedReason) {
        this(wouldAlert, wouldReply, templateId, decisionReason, blockedReason, false, null,
                wouldAlert || wouldReply ? DecisionStatus.WOULD_ACT
                        : blockedReason != null ? DecisionStatus.BLOCKED : DecisionStatus.NO_ACTION);
    }
    public SimulationDecision(boolean wouldAlert, boolean wouldReply, String templateId,
                              String decisionReason, String blockedReason,
                              boolean realActionAllowed, String realBlockedReason) {
        this(wouldAlert, wouldReply, templateId, decisionReason, blockedReason, realActionAllowed, realBlockedReason,
                wouldAlert || wouldReply ? DecisionStatus.WOULD_ACT
                        : blockedReason != null ? DecisionStatus.BLOCKED : DecisionStatus.NO_ACTION);
    }
}
