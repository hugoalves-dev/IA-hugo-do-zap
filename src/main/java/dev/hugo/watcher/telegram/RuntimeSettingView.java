package dev.hugo.watcher.telegram;

import java.util.List;

public record RuntimeSettingView(String key, Object configuredValue, Object effectiveValue, String source,
                                boolean editable, long version, List<String> dependencies,
                                List<String> blockers) {
    public RuntimeSettingView {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }
}
