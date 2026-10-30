package dev.hugo.watcher.simulation;

import dev.hugo.watcher.policy.Situation;
import java.util.Set;

public record Classification(Set<Situation> situations, boolean requestDetected,
                             boolean explicitPersonalReference, boolean groupTriggerMatched,
                             boolean available, dev.hugo.watcher.ai.ClassificationResult.Urgency urgency,
                             double confidence, dev.hugo.watcher.ai.ClassificationResult.Status status,
                             String personalReferenceHash) {
    public Classification(Set<Situation> situations, boolean requestDetected, boolean explicitPersonalReference,
                          boolean groupTriggerMatched, boolean available) {
        this(situations, requestDetected, explicitPersonalReference, groupTriggerMatched, available,
                dev.hugo.watcher.ai.ClassificationResult.Urgency.UNCERTAIN, 0,
                dev.hugo.watcher.ai.ClassificationResult.Status.UNCERTAIN, null);
    }
    public Classification { situations = situations == null ? Set.of() : Set.copyOf(situations); }
    public static Classification unavailable() { return new Classification(Set.of(), false, false, false, false,
            dev.hugo.watcher.ai.ClassificationResult.Urgency.UNCERTAIN, 0,
            dev.hugo.watcher.ai.ClassificationResult.Status.UNAVAILABLE, null); }
    public static Classification greeting() { return new Classification(Set.of(), false, false, false, true); }
    public static Classification contactRequest() { return new Classification(Set.of(Situation.CONTACT_REQUEST), true, false, false, true); }
    public static Classification groupTriggerCritical(boolean personalReference) {
        return new Classification(Set.of(Situation.GROUP_TRIGGER, Situation.CRITICAL_URGENCY), true, personalReference, true, true);
    }
    public static Classification criticalUrgency(boolean personalReference) {
        return new Classification(Set.of(Situation.CRITICAL_URGENCY), true, personalReference, false, true);
    }
}
