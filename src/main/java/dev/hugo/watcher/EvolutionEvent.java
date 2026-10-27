package dev.hugo.watcher;

import java.util.Map;

public record EvolutionEvent(String source, String instance, String eventType, String externalId,
                             Map<String, Object> payload) { }
