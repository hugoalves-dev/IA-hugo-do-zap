package dev.hugo.watcher;

import java.util.Set;

public record IdentityObservation(String observedJid, Set<String> aliases, IdentityEvidence evidence) {
    public IdentityObservation {
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
        evidence = evidence == null ? IdentityEvidence.NONE : evidence;
    }
}
