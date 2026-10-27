package dev.hugo.watcher;

/** Controls whether a validated legacy snapshot is only inspected or applied. */
public enum ImportMode {
    DRY_RUN,
    APPLY
}
