package dev.hugo.watcher.ai;

import java.util.List;

public record ClassificationResult(Intent intent, boolean requestDetected,
                                  boolean notificationRecommended, boolean replyRecommended,
                                  Urgency urgency, List<Evidence> evidence, double confidence,
                                  Status status, String diagnostic) {
    public static final double MIN_CONFIDENCE = 0.65;
    private static final int MAX_EVIDENCE_ITEMS = 3;
    public enum Evidence {
        DIRECT_QUESTION, EXPLICIT_REQUEST, CONTEXTUAL_DEPENDENCY, EXPLICIT_DEADLINE,
        CRITICAL_RISK, VERIFIED_GROUP_TRIGGER, PROMOTIONAL_CONTENT, ALREADY_RESOLVED, NO_ACTION_NEEDED
    }

    public enum Intent { NONE, CONTACT_REQUEST, GROUP_TRIGGER, UNCERTAIN }
    public enum Urgency { LOW, NORMAL, HIGH, CRITICAL, UNCERTAIN }
    public enum Status { CLASSIFIED, UNCERTAIN, UNAVAILABLE }

    public ClassificationResult(Intent intent, boolean requestDetected, Urgency urgency,
                                List<Evidence> evidence, double confidence, Status status, String diagnostic) {
        this(intent, requestDetected, requestDetected, requestDetected, urgency, evidence, confidence, status, diagnostic);
    }

    public ClassificationResult(Intent intent, boolean requestDetected, Urgency urgency,
                                List<Evidence> evidence, double confidence, Status status) {
        this(intent, requestDetected, requestDetected, requestDetected, urgency, evidence, confidence, status, null);
    }

    public ClassificationResult {
        if (intent == null || urgency == null || status == null
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1)
            throw new IllegalArgumentException("invalid classification result");
        evidence = evidence == null ? List.of() : evidence.stream().filter(java.util.Objects::nonNull)
                .distinct().limit(MAX_EVIDENCE_ITEMS).toList();
        if (status == Status.UNAVAILABLE) {
            intent = Intent.UNCERTAIN;
            requestDetected = false;
            notificationRecommended = false;
            replyRecommended = false;
            urgency = Urgency.UNCERTAIN;
            evidence = List.of();
            confidence = 0;
        } else if (confidence < MIN_CONFIDENCE || intent == Intent.UNCERTAIN || urgency == Urgency.UNCERTAIN
                || status == Status.UNCERTAIN) {
            intent = Intent.UNCERTAIN;
            requestDetected = false;
            notificationRecommended = false;
            replyRecommended = false;
            urgency = Urgency.UNCERTAIN;
            evidence = List.of();
            status = Status.UNCERTAIN;
        } else {
            if (replyRecommended && (!notificationRecommended || !requestDetected))
                throw new IllegalArgumentException("reply recommendation requires a detected request and notification recommendation");
            status = Status.CLASSIFIED;
        }
    }

    public static ClassificationResult classified(Intent intent, boolean requestDetected, Urgency urgency,
                                                   List<Evidence> evidence, double confidence) {
        return new ClassificationResult(intent, requestDetected, urgency, evidence, confidence, Status.CLASSIFIED);
    }

    public static ClassificationResult classified(Intent intent, boolean requestDetected,
                                                   boolean notificationRecommended, boolean replyRecommended,
                                                   Urgency urgency, List<Evidence> evidence, double confidence) {
        return new ClassificationResult(intent, requestDetected, notificationRecommended, replyRecommended,
                urgency, evidence, confidence, Status.CLASSIFIED, null);
    }

    public static ClassificationResult uncertain() {
        return new ClassificationResult(Intent.UNCERTAIN, false, false, false,
                Urgency.UNCERTAIN, List.of(), 0, Status.UNCERTAIN, null);
    }

    public static ClassificationResult unavailable() {
        return unavailable("provider_unavailable");
    }
    public static ClassificationResult unavailable(String reason) {
        return new ClassificationResult(Intent.UNCERTAIN, false, false, false,
                Urgency.UNCERTAIN, List.of(), 0, Status.UNAVAILABLE, reason);
    }
}
