package dev.hugo.watcher.simulation;

public record SimulationDecision(boolean wouldAlert, boolean wouldReply, String templateId,
                                 String decisionReason, String blockedReason,
                                 boolean realActionAllowed, String realBlockedReason, DecisionStatus status,
                                 TriageOutcome triageOutcome, boolean notificationRecommended,
                                 boolean replyRecommended, String triageReason, java.util.List<String> triageBlockers) {
    public SimulationDecision {
        triageOutcome = triageOutcome == null ? TriageOutcome.UNDECIDED : triageOutcome;
        triageBlockers = triageBlockers == null ? java.util.List.of() : triageBlockers.stream().distinct().limit(8).toList();
    }

    public SimulationDecision(boolean wouldAlert, boolean wouldReply, String templateId,
                              String decisionReason, String blockedReason, boolean realActionAllowed,
                              String realBlockedReason, DecisionStatus status) {
        this(wouldAlert, wouldReply, templateId, decisionReason, blockedReason, realActionAllowed,
                realBlockedReason, status, TriageOutcome.UNDECIDED, false, false, null, java.util.List.of());
    }

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
