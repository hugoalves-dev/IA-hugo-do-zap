package dev.hugo.watcher.ai;

import java.net.http.HttpRequest;
import java.time.Duration;

/** Injectable transport boundary. Runtime uses {@link JdkProviderTransport}; tests can use an in-memory fake. */
public interface ProviderTransport {
    Response send(HttpRequest request, Duration timeout, int maxResponseBytes);

    record Response(int statusCode, String body) { }
}
