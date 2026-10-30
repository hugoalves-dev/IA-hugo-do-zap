package dev.hugo.watcher.ai;

import java.util.List;

public record ClassificationResult(Intent intent, boolean requestDetected, Urgency urgency,
                                  List<String> evidence, double confidence, Status status) {
    public static final double MIN_CONFIDENCE = 0.65;
    private static final int MAX_EVIDENCE_ITEMS = 3;
    private static final int MAX_EVIDENCE_CHARS = 120;

    public enum Intent { NONE, CONTACT_REQUEST, GROUP_TRIGGER, UNCERTAIN }
    public enum Urgency { LOW, NORMAL, HIGH, CRITICAL, UNCERTAIN }
    public enum Status { CLASSIFIED, UNCERTAIN, UNAVAILABLE }

    public ClassificationResult {
        if (intent == null || urgency == null || status == null
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1)
            throw new IllegalArgumentException("invalid classification result");
        evidence = evidence == null ? List.of() : evidence.stream().filter(java.util.Objects::nonNull)
                .map(String::strip).filter(value -> !value.isEmpty()).limit(MAX_EVIDENCE_ITEMS)
                .map(value -> value.length() > MAX_EVIDENCE_CHARS ? value.substring(0, MAX_EVIDENCE_CHARS) : value)
                .toList();
        if (status == Status.UNAVAILABLE) {
            intent = Intent.UNCERTAIN;
            requestDetected = false;
            urgency = Urgency.UNCERTAIN;
            evidence = List.of();
            confidence = 0;
        } else if (confidence < MIN_CONFIDENCE || intent == Intent.UNCERTAIN || urgency == Urgency.UNCERTAIN
                || status == Status.UNCERTAIN) {
            intent = Intent.UNCERTAIN;
            requestDetected = false;
            urgency = Urgency.UNCERTAIN;
            evidence = List.of();
            status = Status.UNCERTAIN;
        } else {
            status = Status.CLASSIFIED;
        }
    }

    public static ClassificationResult classified(Intent intent, boolean requestDetected, Urgency urgency,
                                                   List<String> evidence, double confidence) {
        return new ClassificationResult(intent, requestDetected, urgency, evidence, confidence, Status.CLASSIFIED);
    }

    public static ClassificationResult uncertain() {
        return new ClassificationResult(Intent.UNCERTAIN, false, Urgency.UNCERTAIN, List.of(), 0, Status.UNCERTAIN);
    }

    public static ClassificationResult unavailable() {
        return new ClassificationResult(Intent.UNCERTAIN, false, Urgency.UNCERTAIN, List.of(), 0, Status.UNAVAILABLE);
    }
}
