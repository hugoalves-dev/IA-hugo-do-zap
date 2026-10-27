package dev.hugo.watcher.ai;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ProviderEndpointPolicy {
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "generativelanguage.googleapis.com", "openrouter.ai");
    private static final Map<String, String> PROVIDER_HOSTS = Map.of(
            "gemini", "generativelanguage.googleapis.com", "openrouter", "openrouter.ai");

    private ProviderEndpointPolicy() { }

    static URI requireSafe(URI endpoint) {
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.getScheme() == null || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("provider endpoint must be an absolute URI without user info, query, or fragment");
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        String host = endpoint.getHost().toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (!"https".equals(scheme) || !ALLOWED_HOSTS.contains(host))
            throw new IllegalArgumentException("provider endpoint must use HTTPS and an official provider host");
        return endpoint;
    }

    static URI requireSafe(URI endpoint, String provider) {
        URI safe = requireSafe(endpoint);
        String expectedHost = PROVIDER_HOSTS.get(provider);
        String actualHost = safe.getHost().toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (expectedHost == null || !expectedHost.equals(actualHost))
            throw new IllegalArgumentException("provider endpoint host does not match provider");
        return safe;
    }
}
