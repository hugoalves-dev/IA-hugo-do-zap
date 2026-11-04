package dev.hugo.watcher.simulation;

public record Decision(boolean wouldAlert, boolean wouldReply, String templateReference,
                       String reason, String blockedReason, boolean realActionAllowed,
                       String realBlockedReason, DecisionStatus status, TriageOutcome triageOutcome,
                       boolean notificationRecommended, boolean replyRecommended,
                       String triageReason, java.util.List<String> triageBlockers) {
    public Decision {
        triageOutcome = triageOutcome == null ? TriageOutcome.UNDECIDED : triageOutcome;
        triageBlockers = triageBlockers == null ? java.util.List.of() : triageBlockers.stream().distinct().limit(8).toList();
    }

    public Decision(boolean wouldAlert, boolean wouldReply, String templateReference,
                    String reason, String blockedReason, boolean realActionAllowed, String realBlockedReason,
                    DecisionStatus status) {
        this(wouldAlert, wouldReply, templateReference, reason, blockedReason, realActionAllowed,
                realBlockedReason, status, TriageOutcome.UNDECIDED, false, false, null, java.util.List.of());
    }

    public Decision(boolean wouldAlert, boolean wouldReply, String templateReference,
                    String reason, String blockedReason, boolean realActionAllowed, String realBlockedReason) {
        this(wouldAlert, wouldReply, templateReference, reason, blockedReason, realActionAllowed,
                realBlockedReason, wouldAlert || wouldReply ? DecisionStatus.WOULD_ACT
                        : blockedReason != null ? DecisionStatus.BLOCKED : DecisionStatus.NO_ACTION);
    }
}
