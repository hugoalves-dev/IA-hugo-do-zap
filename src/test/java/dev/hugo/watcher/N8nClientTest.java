package dev.hugo.watcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class N8nClientTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger acceptedRequests = new AtomicInteger();
    private final AtomicInteger idempotencyHeaders = new AtomicInteger();
    private volatile int status = 503;
    private volatile long responseDelayMillis;
    private N8nClient client;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach void startFakeHttpServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/webhook", exchange -> {
            requests.incrementAndGet();
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            acceptedRequests.incrementAndGet();
            if (exchange.getRequestHeaders().containsKey("Idempotency-Key")) idempotencyHeaders.incrementAndGet();
            if (responseDelayMillis > 0) {
                try { Thread.sleep(responseDelayMillis); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            try { exchange.sendResponseHeaders(status, -1); }
            catch (java.io.IOException ignored) { /* client timeout closes an already accepted request */ }
            exchange.close();
        });
        server.start();
        var properties = new WatcherProperties();
        properties.setN8nUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/webhook");
        properties.setN8nSecret("fixture-secret");
        client = new N8nClient(properties, HttpClient.newHttpClient(), Duration.ofSeconds(2), List.of(Duration.ZERO, Duration.ZERO));
    }

    @AfterEach void stopFakeHttpServer() { if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); }

    @Test void retriesServerFailuresWithoutTreatingThemAsAcknowledgements() {
        boolean sent = client.send(new OutboxAction(1, "key", "archived_messages", "{\"eventType\":\"archived_messages\"}", "[]", 1, null, null, null));

        assertFalse(sent);
        assertEquals(3, requests.get());
        assertEquals("fixture-secret", JsonMapper.builder().build().readTree(receivedBody.get()).get("secret").asString());
    }

    @Test void retriesAfterFakeN8nAcceptedRequestsButResponsesTimedOut() {
        responseDelayMillis = 2_000;
        client = new N8nClient(new WatcherPropertiesWithUrl(server.getAddress().getPort()).properties(),
                HttpClient.newHttpClient(), Duration.ofMillis(500), List.of(Duration.ZERO, Duration.ZERO));

        boolean sent = client.send(new OutboxAction(2, "timeout", "archived_messages", "{}", "[]", 1, null, null, null));

        assertFalse(sent);
        assertEquals(3, requests.get());
        assertEquals(3, acceptedRequests.get(), "fake n8n received every request body before client timeout");
        assertEquals(0, idempotencyHeaders.get(), "no n8n idempotency contract is configured");
    }

    private static final class WatcherPropertiesWithUrl {
        private final WatcherProperties properties = new WatcherProperties();
        private WatcherPropertiesWithUrl(int port) { properties.setN8nUrl("http://127.0.0.1:" + port + "/webhook"); }
        private WatcherProperties properties() { return properties; }
    }
}
