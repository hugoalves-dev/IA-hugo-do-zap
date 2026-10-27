package dev.hugo.watcher.simulation;

public record SimulationDecision(boolean wouldAlert, boolean wouldReply, String templateId,
                                 String decisionReason, String blockedReason) { }
