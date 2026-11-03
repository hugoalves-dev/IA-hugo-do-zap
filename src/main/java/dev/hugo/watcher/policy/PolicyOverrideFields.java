package dev.hugo.watcher.policy;

/** Projection intentionally excludes chat_jid to preserve DISTINCT across merged identity aliases. */
public record PolicyOverrideFields(String profile, String fields) { }
