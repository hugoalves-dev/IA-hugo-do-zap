package dev.hugo.watcher;

public interface InboxRepository {
    Acceptance accept(EvolutionEvent event);
}
