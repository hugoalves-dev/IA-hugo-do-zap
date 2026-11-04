package dev.hugo.watcher.simulation;

/** Semantic recommendation for a classified conversation; it never authorizes delivery. */
public enum TriageOutcome {
    IGNORE,
    NOTIFY_ONLY,
    NOTIFY_AND_REPLY,
    UNDECIDED
}
