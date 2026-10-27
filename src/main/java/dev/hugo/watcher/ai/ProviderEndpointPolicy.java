package dev.hugo.watcher.ai;

import java.net.URI;
import java.util.Locale;

final class ProviderEndpointPolicy {
    private ProviderEndpointPolicy() { }

    static URI requireSafe(URI endpoint) {
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.getScheme() == null || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null)
            throw new IllegalArgumentException("provider endpoint must be an absolute URI without user info, query, or fragment");
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        String host = endpoint.getHost().toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        boolean secure = "https".equals(scheme);
        boolean strictLoopbackHttp = "http".equals(scheme) && ("127.0.0.1".equals(host) || "::1".equals(host));
        if (!secure && !strictLoopbackHttp)
            throw new IllegalArgumentException("provider endpoint must use HTTPS; HTTP is allowed only on literal loopback");
        return endpoint;
    }
}
