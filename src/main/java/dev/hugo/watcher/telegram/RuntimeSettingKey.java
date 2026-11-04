package dev.hugo.watcher.telegram;

/** Explicit allowlist of settings that may have a runtime override. */
public enum RuntimeSettingKey {
    AI_CONTEXT_WINDOW_HOURS("ai.context_window_hours", "INTEGER", 1, 168);

    private final String key;
    private final String type;
    private final int minimum;
    private final int maximum;

    RuntimeSettingKey(String key, String type, int minimum, int maximum) {
        this.key = key;
        this.type = type;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public String key() { return key; }
    public String type() { return type; }
    public int minimum() { return minimum; }
    public int maximum() { return maximum; }

    static RuntimeSettingKey require(String value) {
        for (RuntimeSettingKey key : values()) if (key.key.equals(value)) return key;
        throw new IllegalArgumentException("Setting is not runtime-editable.");
    }
}
