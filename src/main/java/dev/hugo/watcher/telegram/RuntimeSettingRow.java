package dev.hugo.watcher.telegram;

import java.time.Instant;

public record RuntimeSettingRow(String key, String type, String valueJson, boolean overridden,
                               long version, long updatedBy, Instant updatedAt) { }
