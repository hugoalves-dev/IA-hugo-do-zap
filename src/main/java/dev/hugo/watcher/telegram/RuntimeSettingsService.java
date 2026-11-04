package dev.hugo.watcher.telegram;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class RuntimeSettingsService {
    private final RuntimeSettingsRepository repository;
    private final int configuredContextWindowHours;
    private final String configuredOrigin;

    @Autowired
    public RuntimeSettingsService(RuntimeSettingsRepository repository, Environment environment,
                                  @org.springframework.beans.factory.annotation.Value("${watcher.ai.context-window-hours:24}") int configuredContextWindowHours) {
        this(repository, configuredContextWindowHours,
                environment.containsProperty("WATCHER_AI_CONTEXT_WINDOW_HOURS") ? "ENV" : "DEFAULT");
    }

    RuntimeSettingsService(RuntimeSettingsRepository repository, int configuredContextWindowHours) {
        this(repository, configuredContextWindowHours, "ENV");
    }

    private RuntimeSettingsService(RuntimeSettingsRepository repository, int configuredContextWindowHours, String configuredOrigin) {
        if (configuredContextWindowHours < 1 || configuredContextWindowHours > 168)
            throw new IllegalArgumentException("Context window ceiling must be between 1 and 168 hours.");
        this.repository = repository;
        this.configuredContextWindowHours = configuredContextWindowHours;
        this.configuredOrigin = configuredOrigin;
    }

    public List<RuntimeSettingView> list() {
        RuntimeSettingRow row = repository.findAll().get(RuntimeSettingKey.AI_CONTEXT_WINDOW_HOURS.key());
        boolean overridden = row != null && row.overridden();
        int stored = overridden ? parseStored(row) : configuredContextWindowHours;
        int effective = Math.min(stored, configuredContextWindowHours);
        boolean clamped = overridden && stored > configuredContextWindowHours;
        return List.of(new RuntimeSettingView(RuntimeSettingKey.AI_CONTEXT_WINDOW_HOURS.key(),
                configuredContextWindowHours, effective, clamped ? "ENV_CEILING" : overridden ? "RUNTIME" : configuredOrigin, true,
                row == null ? 0 : row.version(),
                List.of("WATCHER_AI_CONTEXT_WINDOW_HOURS", "consentimento do chat", "retenção sensível máxima de 7 dias"),
                clamped ? List.of("runtime_override_above_current_environment_ceiling") : List.of()));
    }

    public int contextWindowHours() {
        return (Integer) list().getFirst().effectiveValue();
    }

    public RuntimeSettingView set(String key, String value, long expectedVersion, long actorId, Instant at) {
        RuntimeSettingKey setting = RuntimeSettingKey.require(key);
        if (actorId <= 0 || at == null) throw new IllegalArgumentException("Actor and timestamp are required.");
        int parsed = parse(value, setting);
        RuntimeSettingRow row = repository.update(setting.key(), setting.type(), Integer.toString(parsed), true,
                expectedVersion, actorId, at);
        return view(row);
    }

    public RuntimeSettingView restore(String key, long expectedVersion, long actorId, Instant at) {
        RuntimeSettingKey setting = RuntimeSettingKey.require(key);
        if (actorId <= 0 || at == null) throw new IllegalArgumentException("Actor and timestamp are required.");
        RuntimeSettingRow row = repository.update(setting.key(), setting.type(),
                Integer.toString(configuredContextWindowHours), false, expectedVersion, actorId, at);
        return view(row);
    }

    private RuntimeSettingView view(RuntimeSettingRow row) {
        int stored = parseStored(row);
        boolean clamped = row.overridden() && stored > configuredContextWindowHours;
        return new RuntimeSettingView(row.key(), configuredContextWindowHours, Math.min(stored, configuredContextWindowHours),
                clamped ? "ENV_CEILING" : row.overridden() ? "RUNTIME" : configuredOrigin, true, row.version(),
                List.of("WATCHER_AI_CONTEXT_WINDOW_HOURS", "consentimento do chat", "retenção sensível máxima de 7 dias"),
                clamped ? List.of("runtime_override_above_current_environment_ceiling") : List.of());
    }

    private int parseStored(RuntimeSettingRow row) {
        if (!"INTEGER".equals(row.type())) throw new IllegalStateException("Stored runtime setting type is invalid.");
        String json = row.valueJson();
        if (json == null || !json.matches("[0-9]{1,3}")) throw new IllegalStateException("Stored runtime setting value is invalid.");
        int value = parse(json, RuntimeSettingKey.AI_CONTEXT_WINDOW_HOURS);
        return value;
    }

    private int parse(String value, RuntimeSettingKey setting) {
        if (value == null || !value.matches("[0-9]{1,3}")) throw new IllegalArgumentException("Value must be an integer.");
        int parsed;
        try { parsed = Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Value must be an integer."); }
        if (parsed < setting.minimum() || parsed > Math.min(setting.maximum(), configuredContextWindowHours))
            throw new IllegalArgumentException("Value exceeds the configured environment ceiling.");
        return parsed;
    }
}
