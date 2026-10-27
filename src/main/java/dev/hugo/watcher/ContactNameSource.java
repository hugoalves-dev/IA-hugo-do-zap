package dev.hugo.watcher;

/** The same precedence tiers used by the TypeScript contact identity resolver. */
public enum ContactNameSource {
    CONTACT_NAME(6), NOTIFY(5), VERIFIED(4), CHAT(3), LEGACY(3), USERNAME(2), PHONE(1);

    private final int rank;
    ContactNameSource(int rank) { this.rank = rank; }
    public int rank() { return rank; }
}
