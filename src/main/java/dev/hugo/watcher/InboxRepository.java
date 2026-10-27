package dev.hugo.watcher;

public interface InboxRepository {
    Acceptance accept(EvolutionEvent event);
    void quarantine(String eventKind, String sourceEventId, String reason);
}
