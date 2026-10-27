package dev.hugo.watcher;

import java.util.Set;

public record CanonicalIdentity(String canonicalJid, Set<String> aliases) {
    public CanonicalIdentity {
        aliases = Set.copyOf(aliases);
    }
}
