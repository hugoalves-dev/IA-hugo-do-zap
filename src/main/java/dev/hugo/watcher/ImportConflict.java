package dev.hugo.watcher;

/** A sanitized conflict summary; source JIDs and message contents are never included. */
public record ImportConflict(String entity, String keyFingerprint, String reason) { }
