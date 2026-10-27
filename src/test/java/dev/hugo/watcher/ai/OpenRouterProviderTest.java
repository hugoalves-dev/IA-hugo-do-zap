package dev.hugo.watcher.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenRouterProviderTest {
    private HttpServer server;

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void enforcesZdrAndDataCollectionDenyInEveryRequest() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        server = server(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            reply(exchange, 200, """
                    {"choices":[{"message":{"content":"{\\"intent\\":\\"CONTACT_REQUEST\\",\\"requestDetected\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.91}"}}]}
                    """);
        });

        var result = provider(allow()).classify(new ClassificationInput(List.of("hello")));

        assertEquals(ClassificationResult.Status.CLASSIFIED, result.status());
        assertTrue(body.get().contains("\"zdr\":true"));
        assertTrue(body.get().contains("\"data_collection\":\"deny\""));
        assertTrue(body.get().contains("\"allow_fallbacks\":false"));
        assertTrue(body.get().contains("untrusted"));
        assertFalse(body.get().contains("\"personalReference\""));
        assertFalse(body.get().contains("\"authorization\""));
    }

    @Test void unavailablePrivateRouteFailsClosedWithoutRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> { requests.incrementAndGet(); reply(exchange, 404, "no eligible endpoints"); });

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(allow()).classify(new ClassificationInput(List.of("private text"))));

        assertEquals(ClassificationProviderException.Kind.PRIVACY_ROUTE_UNAVAILABLE, error.kind());
        assertEquals(1, requests.get());
    }

    @Test void modelCannotReturnPermissionOrPersonalReferenceFields() throws Exception {
        server = server(exchange -> reply(exchange, 200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"CONTACT_REQUEST\\",\\"requestDetected\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.91,\\"authorized\\":true}"}}]}
                """));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(allow()).classify(new ClassificationInput(List.of("hello"))));

        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void inconsistentSchemaFieldsFailClosed() throws Exception {
        server = server(exchange -> reply(exchange, 200, """
                {"choices":[{"message":{"content":"{\\"intent\\":\\"NONE\\",\\"requestDetected\\":true,\\"urgency\\":\\"NORMAL\\",\\"evidence\\":[],\\"confidence\\":0.91}"}}]}
                """));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(allow()).classify(new ClassificationInput(List.of("hello"))));

        assertEquals(ClassificationProviderException.Kind.INVALID_RESPONSE, error.kind());
    }

    @Test void deniedAuthorizationMakesNoHttpCall() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> { requests.incrementAndGet(); reply(exchange, 200, "{}"); });

        assertThrows(ClassificationProviderException.class,
                () -> provider((name, input) -> false).classify(new ClassificationInput(List.of("hello"))));
        assertEquals(0, requests.get());
    }

    @Test void rejectsInsecureRemoteEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> new OpenRouterProvider(
                URI.create("http://api.example.com/api/v1/chat/completions"), "test/model", "fake-key",
                HttpClient.newHttpClient(), allow(), Duration.ofSeconds(2)));
    }

    @Test void oversizedResponseFailsClosedAtBodyLimit() throws Exception {
        server = server(exchange -> reply(exchange, 200, "x".repeat(1024 * 1024)));

        var error = assertThrows(ClassificationProviderException.class,
                () -> provider(allow()).classify(new ClassificationInput(List.of("hello"))));

        assertEquals("RESPONSE_TOO_LARGE", error.kind().name());
    }

    private OpenRouterProvider provider(ProviderAuthorization authorization) {
        return new OpenRouterProvider(endpoint(), "test/model", "fake-key", HttpClient.newHttpClient(),
                authorization, Duration.ofSeconds(2));
    }

    private URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/chat/completions"); }
    private static ProviderAuthorization allow() { return (name, input) -> true; }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
