package dev.hugo.watcher.ai;

/** Authorization must reserve budget and approve data handling before a paid provider call. */
@FunctionalInterface
public interface ProviderAuthorization {
    boolean authorize(String provider, ClassificationInput input);

    static ProviderAuthorization denyAll() { return (provider, input) -> false; }
}
